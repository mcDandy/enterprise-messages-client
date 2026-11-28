package cz.mkdaniel.enterprisemessenger.ui.message;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentMessagesBinding;
import cz.mkdaniel.enterprisemessenger.databinding.ItemMessageReceivedBinding;
import cz.mkdaniel.enterprisemessenger.databinding.ItemMessageSentBinding;
import cz.mkdaniel.enterprisemessenger.net.AccountStore;
import cz.mkdaniel.enterprisemessenger.net.LocalNetworkPermission;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;
import cz.mkdaniel.enterprisemessenger.notification.NotificationHelper;

/**
 * Chat screen showing messages in a channel of a virtual server.
 * Communicates with the one physical server via WebSocket using ServerConnectionManager; the
 * address is never a navigation argument, only the channel selected here is.
 * Displays a red underline on the message input field if the server cannot be reached.
 */
public class MessagesFragment extends Fragment implements ServerConnectionManager.ConnectionListener {

    private static final String TAG = "MessagesFragment";

    /**
     * How long an explicit reason may cover a connection drop that follows it.
     */
    private static final long DROP_EXPLAINED_MILLIS = 2_000L;
    private static final long PENDING_RECONCILIATION_WINDOW_MILLIS = 60_000L;
    /**
     * Android 17 (targetSdk 37) blocks every socket to a LAN address until the user grants
     * ACCESS_LOCAL_NETWORK; without it connect() just times out. Requested before connecting.
     * <p>
     * Registered in {@link #onViewCreated} rather than as a field initializer, because registering
     * during construction leaks {@code this} into the fragment's activity-result state before the
     * fragment is built, where a restart replaying a pending result would reach a half-constructed
     * fragment.
     */
    private ActivityResultLauncher<String> localNetworkPermissionLauncher;
    private FragmentMessagesBinding binding;
    private MessageAdapter adapter;
    private ServerConnectionManager connectionManager;
    private long serverId;
    private String roomId;
    private String roomName;
    private LinearLayoutManager messageLayoutManager;
    private int historyPage = 1;
    private boolean loadingOlderMessages;
    private boolean noMoreOlderMessages;
    /**
     * Until when an error we were told stands in for the generic "cannot be reached" toast. A server
     * that closes our session is reachable, and repeating the network message over the real reason
     * sends the user looking in the wrong place. Short enough that an unrelated error reported while
     * connected cannot silence a later, genuinely unexplained drop.
     */
    private long dropWasExplainedUntil = 0L;
    private final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * Set once the server says this device is no longer a member of the channel on screen. It stops
     * the poll and locks the input, and it is what {@code onResume()} clears so returning to the
     * screen after an administrator adds the user back recovers without restarting the app.
     */
    private boolean removedFromChannel = false;
    private final Runnable pollRunnable = new Runnable() {
        /**
         * Runs the scheduled message polling task.
         *
         */
        @Override
        public void run() {
            if (removedFromChannel) {
                // Nothing to poll: the channel is closed to us and the server would refuse every
                // request. The tick is not rescheduled - handleRemovedFromChannel stopped it.
                return;
            }
            pollLatestMessages();
            // The server never announces a removal, so asking is the only signal. Once per tick
            // keeps that to one small request per three seconds while this screen is open.
            verifyChannelMembership();
            pollHandler.postDelayed(this, 3_000L);
        }
    };

    /**
     * Asks whether this device is still a member of the channel on screen, and locks the screen down
     * when the answer is no.
     */
    private void verifyChannelMembership() {
        if (connectionManager == null || roomId == null || roomId.isEmpty()) {
            return;
        }
        connectionManager.verifyStillMemberOfChannel(
                ServerConnectionManager.parseChannelId(roomId),
                new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(Void result) {
                        // Still a member, or the server could not be asked. Either way the input
                        // stays as it is; the next tick asks again.
                    }

                    @Override
                    public void onFailure(String error) {
                        handleRemovedFromChannel(error);
                    }
                });
    }

    /**
     * Reacts to the server no longer listing this device in the channel: the history stays readable,
     * but nothing more can be sent, so the input is locked and the poll stops.
     * <p>
     * The screen is deliberately not popped. The user is looking at a conversation that has just
     * become read-only under him, and yanking the screen away loses the context of why his message
     * stopped going through.
     *
     * @param error the reason to show the user
     */
    private void handleRemovedFromChannel(String error) {
        if (!isAdded() || removedFromChannel) {
            return;
        }
        removedFromChannel = true;
        pollHandler.removeCallbacks(pollRunnable);

        if (binding != null) {
            binding.edittextMessageInput.setEnabled(false);
            binding.buttonSendMessage.setEnabled(false);
            updateInputUnderline(false);
        }
        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
    }

    /**
     * Fetches and displays the latest messages for the visible channel.
     *
     */
    private void pollLatestMessages() {
        if (connectionManager == null || roomId == null || roomId.isEmpty()) {
            return;
        }
        connectionManager.fetchMessagePage(
                ServerConnectionManager.parseChannelId(roomId),
                0,
                50,
                new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param pageMessages the page messages associated with this callback
                     */
                    @Override
                    public void onResult(List<Message> pageMessages) {
                        if (binding == null || adapter == null || pageMessages.isEmpty()) {
                            return;
                        }

                        final List<Message> merged = mergeWithLocalMessages(pageMessages);

                        boolean isAtBottom = false;
                        if (messageLayoutManager != null) {
                            int lastVisible = messageLayoutManager.findLastVisibleItemPosition();
                            int totalItemCount = adapter.getItemCount();
                            isAtBottom = lastVisible >= totalItemCount - 3;
                        }

                        final boolean autoScroll = isAtBottom;
                        adapter.submitList(merged, () -> {
                            if (binding != null && messageLayoutManager != null && autoScroll && !merged.isEmpty()) {
                                binding.recyclerviewMessages.scrollToPosition(merged.size() - 1);
                            }
                        });

                        sendReadReceiptForLatest();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        Log.d(TAG, "Poll failed: " + error);
                    }
                });
    }

    /**
     * Handles the create view event.
     *
     * @param inflater the layout inflater used to create the view
     * @param container the parent view that will contain the created view
     * @param savedInstanceState the saved instance state to restore, or null when creating the screen for the first time
     * @return the on create view result
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    /**
     * Handles the view created event.
     *
     * @param view the view to create, inspect, or update
     * @param savedInstanceState the saved instance state to restore, or null when creating the screen for the first time
     */
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        localNetworkPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (isGranted) {
                        Log.d(TAG, "ACCESS_LOCAL_NETWORK granted, connecting to the deployment server");
                        startConnection();
                    } else {
                        Log.w(TAG, "ACCESS_LOCAL_NETWORK denied - cannot reach the deployment server");
                        updateInputUnderline(false);
                    }
                });

        // --- Arguments setup (virtual server and channel) ---
        // No key travels in the arguments: the channel's key is read from the sealed registry when
        // joinChannel() runs, so it never appears in a Bundle, a log line or a saved instance state.
        if (getArguments() != null) {
            serverId = getArguments().getLong("serverId", -1L);
            roomId = getArguments().getString("roomId", "");
            roomName = getArguments().getString("roomName", "");
        }

        Log.d(TAG, "Opening MessagesFragment for virtual server " + serverId
                + ", room=" + roomName + " (" + roomId + ")");

        // --- RecyclerView setup ---
        messageLayoutManager = new LinearLayoutManager(requireContext());
        adapter = new MessageAdapter();

        binding.recyclerviewMessages.setLayoutManager(messageLayoutManager);
        binding.recyclerviewMessages.setAdapter(adapter);
        // The adapter is built here, so this is the one place it can be handed the callback that
        // opens the per-message delivery dialog.
        adapter.setOnSentMessageLongClickListener(this::showDeliveryStatusDialog);
        binding.recyclerviewMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            /**
             * Handles scrolling in the message list.
             *
             * @param recyclerView the list view whose scroll state changed
             * @param dx the horizontal scroll distance
             * @param dy the vertical scroll distance
             */
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy >= 0 || loadingOlderMessages || noMoreOlderMessages
                        || messageLayoutManager == null || adapter == null) {
                    return;
                }

                int firstVisible = messageLayoutManager.findFirstVisibleItemPosition();
                if (firstVisible <= 2) {
                    loadOlderMessages();
                }
            }
        });

        // --- Server Connection setup ---
        connectionManager = ServerConnectionManager.getInstance(requireContext());
        connectionManager.addListener(this);

        // Local traffic is dropped by the platform until ACCESS_LOCAL_NETWORK is held (targetSdk 37),
        // so the permission is asked for before dialling - but only when the deployment really is a
        // private address such as 192.168.x.x. A server on the public internet needs nothing beyond
        // INTERNET, and asking anyway would charge the user a decision he never had to make.
        if (LocalNetworkPermission.mayConnect(requireContext(), ServerConnectionManager.WS_HOST)) {
            startConnection();
        } else {
            Log.w(TAG, "Server " + ServerConnectionManager.WS_HOST + " is local and ACCESS_LOCAL_NETWORK "
                    + "is not held yet, requesting it before connecting");
            LocalNetworkPermission.requestUsing(localNetworkPermissionLauncher);
        }

        // Set initial underline state based on connection
        updateInputUnderline(connectionManager.isConnected());

        // --- Send button & text field ---
        ImageButton sendButton = binding.buttonSendMessage;
        EditText inputField = binding.edittextMessageInput;

        sendButton.setOnClickListener(v -> sendMessageFromInput(inputField));

        inputField.setOnEditorActionListener((v, actionId, event) -> {
            sendMessageFromInput(inputField);
            return true;
        });
    }

    /**
     * Opens the session to the physical server - it takes no address, that is compile-time
     * configuration - and selects this channel on it.
     */
    private void startConnection() {
        if (connectionManager == null) {
            return;
        }

        connectionManager.connect();
        connectionManager.joinChannel(roomId);
        historyPage = 1;
        loadingOlderMessages = false;
        noMoreOlderMessages = false;
        loadMessagePage(0, false);
    }

    /**
     * Handles the resume event.
     *
     */
    @Override
    public void onResume() {
        super.onResume();
        long channelId = ServerConnectionManager.parseChannelId(roomId);
        // Returning to the screen re-arms the poll and the input. An administrator can add the user
        // back to a channel he was removed from, and the next tick then lets him write again - no
        // restart, because the lock is only this flag plus the two disabled views.
        removedFromChannel = false;
        if (binding != null) {
            binding.edittextMessageInput.setEnabled(true);
            binding.buttonSendMessage.setEnabled(true);
        }
        // The channel on screen is the one whose messages are read, so it must not notify, and the
        // notification it left behind while the user was elsewhere is now obsolete
        if (connectionManager != null) {
            connectionManager.setVisibleChannel(channelId);
            // The server reports a member's cursor only when it moves, so messages delivered
            // before this screen opened would stay unmarked until someone sent something. One
            // listing fills in what is already true; live frames take over afterwards.
            connectionManager.fetchPeerDeliveryCursors(channelId);
        }
        NotificationHelper.cancelNotificationForChannel(requireContext(), channelId);
        pollHandler.postDelayed(pollRunnable, 3_000L);
    }

    /**
     * Handles the pause event.
     *
     */
    @Override
    public void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
        if (connectionManager != null) {
            connectionManager.setVisibleChannel(-1L);
        }
    }

    /**
     * Updates the input underline.
     *
     * @param isConnected whether the connection is currently active
     */
    private void updateInputUnderline(boolean isConnected) {
        if (binding == null) return;
        if (isConnected) {
            binding.cardInputContainer.setStrokeColor(requireContext().getColor(R.color.outline_border));
        } else {
            binding.cardInputContainer.setStrokeColor(Color.RED);
        }
    }

    /**
     * Loads the older messages.
     *
     */
    private void loadOlderMessages() {
        if (connectionManager == null || loadingOlderMessages || noMoreOlderMessages) {
            return;
        }

        loadMessagePage(historyPage, true);
    }

    /**
     * Loads the message page.
     *
     * @param page the zero-based page number to retrieve
     * @param loadingOlder whether older messages are being loaded
     */
    private void loadMessagePage(int page, boolean loadingOlder) {
        if (connectionManager == null) {
            return;
        }

        if (loadingOlder) {
            loadingOlderMessages = true;
        }
        final int previousCount = adapter == null ? 0 : adapter.getCurrentList().size();
        final int firstVisible = messageLayoutManager == null
                ? 0 : messageLayoutManager.findFirstVisibleItemPosition();

        connectionManager.fetchMessagePage(
                ServerConnectionManager.parseChannelId(roomId),
                page,
                50,
                new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param pageMessages the page messages associated with this callback
                     */
                    @Override
                    public void onResult(List<Message> pageMessages) {
                        if (loadingOlder) {
                            loadingOlderMessages = false;
                            historyPage++;
                        }
                        if (binding == null || adapter == null) return;

                        if (pageMessages.isEmpty()) {
                            noMoreOlderMessages = true;
                            return;
                        }

                        final List<Message> merged = mergeWithLocalMessages(pageMessages);

                        adapter.submitList(merged, () -> {
                            if (binding != null && messageLayoutManager != null) {
                                if (loadingOlder) {
                                    int addedCount = merged.size() - previousCount;
                                    messageLayoutManager.scrollToPosition(
                                            Math.max(0, firstVisible + addedCount));
                                } else if (!merged.isEmpty()) {
                                    binding.recyclerviewMessages.scrollToPosition(merged.size() - 1);
                                }
                            }
                        });

                        if (pageMessages.size() < 50) {
                            noMoreOlderMessages = true;
                        }

                        sendReadReceiptForLatest();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        if (loadingOlder) {
                            loadingOlderMessages = false;
                        }
                        if (isAdded()) {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * Combines server messages with locally pending messages.
     *
     * @param serverMessages the messages returned by the server
     * @return the merge with local messages collection
     */
    private List<Message> mergeWithLocalMessages(List<Message> serverMessages) {
        final List<Message> merged = new ArrayList<>(serverMessages);
        final Set<Message> matchedServerMessages = new HashSet<>();
        for (Message localMessage : adapter.getCurrentList()) {
            boolean alreadyPresent = false;
            boolean confirmedByServer = false;
            for (Message message : merged) {
                if (message.getId().equals(localMessage.getId())) {
                    alreadyPresent = true;
                    break;
                }
                if (localMessage.isPending()
                        && !matchedServerMessages.contains(message)
                        && "You".equals(message.getSender())
                        && localMessage.getText().equals(message.getText())
                        && Math.abs(message.getTimestamp() - localMessage.getTimestamp())
                        <= PENDING_RECONCILIATION_WINDOW_MILLIS) {
                    confirmedByServer = true;
                    matchedServerMessages.add(message);
                    // The server's copy is kept, but under the id the row already carries, so the
                    // diff recognises it as the same item and the bubble does not blink as the
                    // pending one is taken over. The server id comes along with it, so the read
                    // receipt can reach a message the user wrote himself.
                    final int position = merged.indexOf(message);
                    merged.set(position, new Message(
                            localMessage.getId(),
                            message.getServerId(),
                            message.getText(),
                            message.getSender(),
                            message.getTimestamp(),
                            false,
                            true));
                    break;
                }
            }
            if (!alreadyPresent && !confirmedByServer) {
                merged.add(localMessage);
            }
        }
        merged.sort(java.util.Comparator.comparingLong(Message::getTimestamp));
        return merged;
    }

    /**
     * Sends the message from input.
     *
     * @param inputField the text input field to process
     */
    private void sendMessageFromInput(EditText inputField) {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        if (!connectionManager.isConnected()) {
            updateInputUnderline(false);
            Toast.makeText(requireContext(), R.string.error_server_unreachable, Toast.LENGTH_SHORT).show();
            return;
        }

        String messageId = UUID.randomUUID().toString();
        Message sent = new Message(messageId, text, "You", System.currentTimeMillis(), true);
        List<Message> updated = new ArrayList<>(adapter.getCurrentList());
        updated.add(sent);
        scrollToLatest(updated.size() - 1);

        // The server queues the encrypted message for other channel members, not back to the sender.
        connectionManager.sendChatMessage(text);

        inputField.setText("");
    }

    /**
     * Handles the connection state changed event.
     *
     * @param connected whether the WebSocket is connected
     * @param ip the server IP address or host
     */
    @Override
    public void onConnectionStateChanged(boolean connected, String ip) {
        if (!isAdded()) return;
        Log.d(TAG, "Connection state changed: connected=" + connected + ", ip=" + ip);
        updateInputUnderline(connected);
        if (connected) {
            dropWasExplainedUntil = 0L;
            Toast.makeText(getContext(), "Connected to server: " + ip, Toast.LENGTH_SHORT).show();
        } else {
            if (System.currentTimeMillis() > dropWasExplainedUntil) {
                Toast.makeText(getContext(), R.string.error_server_unreachable, Toast.LENGTH_SHORT).show();
            }

            dropWasExplainedUntil = 0L;
        }
    }

    /**
     * Handles the message received event.
     *
     * @param message the message to process
     */
    @Override
    public void onMessageReceived(Message message) {
        if (!isAdded()) return;
        for (Message existing : adapter.getCurrentList()) {
            if (existing.getId().equals(message.getId())) {
                return;
            }
        }
        List<Message> updated = new ArrayList<>(adapter.getCurrentList());
        updated.add(message);
        scrollToLatest(updated.size() - 1);
        sendReadReceiptForLatest();
    }

    /**
     * Peer delivery cursors moved. The bubble's background opacity is derived at bind time from
     * these cursors rather than stored per message, so no list has to be rebuilt - the rows are
     * repainted and re-read the new state.
     *
     * @param cursors per-peer message-delivery cursors
     * @param peerCount the number of other members in the channel
     */
    @Override
    public void onPeerDeliveryProgress(Map<Long, Long> cursors, int peerCount) {
        if (!isAdded() || adapter == null) {
            return;
        }
        adapter.setPeerDelivery(cursors, peerCount);
    }

    /**
     * Handles the message replaced event.
     *
     * @param message the message to process
     */
    @Override
    public void onMessageReplaced(Message message) {
        if (!isAdded() || adapter == null) return;

        final List<Message> current = adapter.getCurrentList();
        final int index = indexOfMessageId(current, message.getId());
        if (index < 0) {
            // The poll already replaced the row, or the user left the channel. Either way the
            // server's copy is the one on screen and nothing has to be done.
            return;
        }

        final List<Message> updated = new ArrayList<>(current);
        updated.set(index, message);
        adapter.submitList(updated);
        sendReadReceiptForLatest();
    }

    /**
     * Position of the message with {@code id}, or -1 when the list does not hold it. The id is
     * unique per message, so the first hit is the only one.
     *
     * @param messages the messages to display or process
     * @param id the server-assigned or local identifier
     * @return the index of message identifier result
     */
    private static int indexOfMessageId(List<Message> messages, String id) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reports the newest message of this channel as read, and only when that position is ahead of the
     * one already reported. The cursor lives in {@link AccountStore} rather than in this screen, so
     * reopening a channel without new messages stays silent: the server treats a repeated position as
     * a refused receipt and answers with an error packet, which would surface as a bogus failure.
     * Only {@link Message#getServerId()} is considered: it is the position the server itself
     * handed out, whereas {@link Message#getId()} is also used for messages this device wrote,
     * which the server has never seen.
     */
    private void sendReadReceiptForLatest() {
        if (!isResumed() || connectionManager == null || roomId == null || roomId.isEmpty()) {
            return;
        }
        if (adapter == null || adapter.getCurrentList().isEmpty()) {
            return;
        }

        long newest = Message.NO_SERVER_ID;
        for (Message message : adapter.getCurrentList()) {
            newest = Math.max(newest, message.getServerId());
        }

        if (newest <= 0) {
            return;
        }

        final long channelId = ServerConnectionManager.parseChannelId(roomId);
        final AccountStore accountStore = new AccountStore(requireContext());
        if (newest <= accountStore.getLastReadMessageId(channelId)) {
            return;
        }

        accountStore.saveLastReadMessageId(channelId, newest);
        connectionManager.sendReadReceipt(channelId, newest);
    }

    /**
     * Shows who has read, who merely has, and who is still missing the message the user
     * long-pressed.
     * <p>
     * The status is fetched rather than read off the adapter's cursors, because the cursors say how
     * far a member got but never that he is still in the channel - only the member listing knows
     * that, and without it a member who has acknowledged nothing would vanish from the dialog
     * instead of showing up as pending. The fetch is on the network, so the dialog opens only once
     * the answer lands; a failure is a toast rather than an empty dialog, because an empty list of
     * readers cannot be told apart from a channel where nobody has read it.
     *
     * @param message the message whose delivery state to show
     */
    private void showDeliveryStatusDialog(final Message message) {
        if (!isAdded() || connectionManager == null || roomId == null || roomId.isEmpty()) {
            return;
        }
        if (message.getServerId() <= 0) {
            // No server id means the server has never seen the message, so no member can have it
            // and there is nothing to ask about.
            Toast.makeText(requireContext(), R.string.delivery_status_no_server_id,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final long channelId = ServerConnectionManager.parseChannelId(roomId);
        connectionManager.getDeliveryStatus(channelId, message.getServerId(),
                new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param status the per-recipient delivery state to display
                     */
                    @Override
                    public void onResult(ServerConnectionManager.MessageDeliveryStatus status) {
                        if (!isAdded()) {
                            return;
                        }
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.dialog_delivery_status_title)
                                .setMessage(buildDeliveryStatusText(status))
                                .setPositiveButton(R.string.delivery_status_action_close, null)
                                .show();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        if (!isAdded()) {
                            return;
                        }
                        Log.w(TAG, "Could not load delivery status of message "
                                + message.getServerId() + ": " + error);
                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Renders the three sections of the delivery dialog. Every section is always present, empty
     * ones included, so "nobody has read it" reads as an answer rather than as a missing feature.
     *
     * @param status the per-recipient delivery state
     * @return the delivery status text collection
     */
    private CharSequence buildDeliveryStatusText(
            ServerConnectionManager.MessageDeliveryStatus status) {
        final StringBuilder text = new StringBuilder();
        appendDeliverySection(text, getString(R.string.delivery_status_section_read), status.readBy);
        appendDeliverySection(text, getString(R.string.delivery_status_section_delivered),
                status.deliveredTo);
        appendDeliverySection(text, getString(R.string.delivery_status_section_pending),
                status.pending);

        return text;
    }

    /**
     * Appends one {@code Section\nname\nname} block, or {@code Section\nNobody yet} when the list
     * is empty, and a blank line after it to separate it from the next section.
     *
     * @param text the builder to append to
     * @param title the section heading
     * @param names the members in this section
     */
    private void appendDeliverySection(final StringBuilder text, final String title,
                                       final List<String> names) {
        text.append(title).append('\n');
        if (names.isEmpty()) {
            text.append(getString(R.string.delivery_status_nobody));
        } else {
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    text.append('\n');
                }
                text.append(names.get(i));
            }
        }
        text.append("\n\n");
    }

    /**
     * Handles the error event.
     *
     * @param errorMessage the error message to display or report
     */
    @Override
    public void onError(String errorMessage) {
        if (!isAdded()) return;
        Log.e(TAG, "Server connection error: " + errorMessage);
        updateInputUnderline(false);

        // Every error the manager reports is the user's problem to know about (a missing channel key
        // means his message was not sent), and none of them was visible until now.
        dropWasExplainedUntil = System.currentTimeMillis() + DROP_EXPLAINED_MILLIS;
        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show();
    }

    /**
     * Handles the destroy view event.
     *
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        pollHandler.removeCallbacks(pollRunnable);
        if (connectionManager != null) {
            connectionManager.removeListener(this);
        }
        binding = null;
    }

    // ---------- adapter & view holder ----------

    /**
     * Background of a bubble the user wrote, fading in as the message reaches more recipients.
    /**
     * Interpolates between a lighter tint and the full solid bubble color based on delivery progress.
     *
     * @param color1 starting color
     * @param color2 ending color
     * @param fraction interpolation fraction between 0 and 1
     * @return the interpolated color
     */
    private static int interpolateColor(int color1, int color2, float fraction) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int) (r1 + fraction * (r2 - r1));
        int g = (int) (g1 + fraction * (g2 - g1));
        int b = (int) (b1 + fraction * (b2 - b1));

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Returns the bubble color for sent messages, tinted lighter when unacknowledged and full solid when acknowledged by all.
     *
     * @param context the Android context used to access application resources and services
     * @param accepted whether the operation was accepted
     * @param recipients the recipients associated with the message
     * @param peerCount the number of other members in the channel
     * @return the bubble color result
     */
    private static int bubbleColor(android.content.Context context, boolean accepted,
                                   int recipients, int peerCount) {
        final int solid = androidx.core.content.ContextCompat.getColor(context,
                R.color.msg_bubble_sent);
        if (!accepted) {
            return solid;
        }
        final int lightTint = androidx.core.content.ContextCompat.getColor(context,
                R.color.msg_bubble_sent_unack);

        final int steps = Math.max(peerCount, 1);
        final float fraction = Math.min(1f, Math.max(0f, (float) recipients / steps));

        return interpolateColor(lightTint, solid, fraction);
    }

    /**
     * Computes a progress step representing the bubble color state for diff comparisons.
     *
     * @param recipients the recipients associated with the message
     * @param peerCount the number of other members in the channel
     * @return the bubble alpha result
     */
    private static int bubbleAlpha(int recipients, int peerCount) {
        final int steps = Math.max(peerCount, 1);
        final float fraction = Math.min(1f, Math.max(0f, (float) recipients / steps));
        return (int) (100 * fraction);
    }

    /**
     * Jumps the list to the newest message once the list carrying it is on screen.
     * <p>
     * Deferred to the next frame rather than run in {@code submitList}'s callback. That callback
     * fires while the diff is still dispatching its updates, and starting a smooth scroll from
     * there throws - {@code LinearLayoutManager} refuses to begin a {@code SmoothScroller} while it
     * is computing a layout or scrolling. A plain jump is tolerated, a smooth one is not, which is
     * why only the paths that scrolled smoothly ever crashed.
     *
     * @param position the item position in the adapter
     */
    private void scrollToLatest(int position) {
        if (binding == null || position < 0) {
            return;
        }
        binding.recyclerviewMessages.post(
                () -> binding.recyclerviewMessages.smoothScrollToPosition(position));
    }

    /**
     * Decides when a row has to be redrawn, and holds the peer delivery cursors that decide it.
     * <p>
     * The cursors live here rather than on the adapter because the diff runs against this object,
     * and a message's rendered opacity depends on them: two {@link Message} instances that are
     * equal in every field must still count as different content when the number of recipients
     * reaching them has moved, or a re-submitted list would diff as unchanged and the bubbles
     * would keep their old opacity.
     */
    private static class MessageDeliveryCallback extends DiffUtil.ItemCallback<Message> {

        /**
         * Each other member's user id mapped to the highest message id their client has
         * acknowledged. Counting the entries that reach a given message gives how many recipients
         * have it, which drives the bubble's opacity.
         */
        private volatile Map<Long, Long> peerCursors = new HashMap<>();
        /**
         * How many other members the channel has. Not derivable from {@link #peerCursors}, because
         * a member who has acknowledged nothing has no entry, and would otherwise be counted as
         * having left the channel.
         */
        private volatile int peerCount = 0;

        /**
         * Sets the peer delivery.
         *
         * @param cursors per-peer message-delivery cursors
         * @param peerCount the number of other members in the channel
         */
        void setPeerDelivery(Map<Long, Long> cursors, int peerCount) {
            this.peerCursors = new HashMap<>(cursors);
            this.peerCount = peerCount;
        }

        /**
         * Returns the number of other channel members.
         *
         * @return the peer count result
         */
        int peerCount() {
            return peerCount;
        }

        /**
         * Counts the peers whose delivery cursor reaches the message.
         *
         * @param message the message to process
         * @return the recipients of result
         */
        int recipientsOf(Message message) {
            final long id = message.getServerId();
            if (id <= 0) {
                return 0;
            }
            int reached = 0;
            for (final Long cursor : peerCursors.values()) {
                if (cursor >= id) {
                    reached++;
                }
            }
            return reached;
        }

        /**
         * Compares message identifiers to determine whether two list items represent the same message.
         *
         * @param oldItem the previous item being compared
         * @param newItem the updated item being compared
         * @return whether the are items the same condition holds
         */
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.getId().equals(newItem.getId());
        }

        /**
         * Compares message contents and delivery state to determine whether a list row needs updating.
         *
         * @param oldItem the previous item being compared
         * @param newItem the updated item being compared
         * @return whether the are contents the same condition holds
         */
        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.getText().equals(newItem.getText())
                    && oldItem.getSender().equals(newItem.getSender())
                    && oldItem.getTimestamp() == newItem.getTimestamp()
                    // A message the user wrote changes colour and mark when the server accepts it,
                    // so that has to count as a content change or the row never repaints from
                    // grey to normal.
                    && oldItem.isDelivered() == newItem.isDelivered()
                    // The opacity a row renders with, recomputed for both sides. Comparing the
                    // recipient count alone is not enough: in a channel whose size just changed,
                    // the same count can map to a different opacity.
                    && bubbleAlpha(recipientsOf(oldItem), peerCount)
                    == bubbleAlpha(recipientsOf(newItem), peerCount);
        }
    }

    /**
     * Represents message adapter data or behavior used by the application.
     *
     */
    private static class MessageAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_SENT = 1;
        private static final int VIEW_TYPE_RECEIVED = 2;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        /**
         * The callback the {@code ListAdapter} diffs with, kept as a field because it also holds
         * the delivery cursors the bind reads. It is handed to {@code super} rather than created
         * alongside: a second instance would receive the cursors while the diff consulted an empty
         * one, and no row would ever repaint.
         */
        private final MessageDeliveryCallback deliveryCallback;
        /**
         * Opens the delivery dialog for the message the user long-pressed. Held as a field rather
         * than reached through {@code itemView.getContext()} because the holder is static and has
         * no reference to the fragment, and a cast there would break the moment the list is shown
         * somewhere other than {@code MessagesFragment}.
         */
        private OnSentMessageLongClickListener onSentMessageLongClickListener;

        /**
         * Called when the user long-presses a bubble this device wrote.
         */
        interface OnSentMessageLongClickListener {
            /**
             * Handles the long click on a sent message.
             *
             * @param message the message that was long-pressed
             */
            void onSentMessageLongClick(Message message);
        }

        /**
         * Sets the listener. Sets on sent message long click.
         *
         * @param listener the listener to register, or {@code null} to disable the gesture
         */
        void setOnSentMessageLongClickListener(OnSentMessageLongClickListener listener) {
            this.onSentMessageLongClickListener = listener;
        }

        /**
         * Replaces the peer delivery state and repaints by re-submitting the current list.
         * <p>
         * Not {@code notifyDataSetChanged()}: that throws when it lands while the RecyclerView is
         * laying out or scrolling, and a delivery cursor arrives from a posted handler that can
         * coincide with either - most visibly on a slow emulator, where the window is wider. Going
         * through {@code submitList} puts the repaint on the same path the message list already
         * uses, so it is serialised against the diff instead of cutting across it.
         * <p>
         * The list is handed back as a new instance because {@code ListAdapter} ignores a
         * submission of the list it already holds; the cursors are not part of {@link Message}, so
         * a diff of identical items would otherwise compute no change and nothing would repaint.
         *
         * @param cursors per-peer message-delivery cursors
         * @param peerCount the number of other members in the channel
         */
        void setPeerDelivery(Map<Long, Long> cursors, int peerCount) {
            deliveryCallback.setPeerDelivery(cursors, peerCount);
            submitList(new ArrayList<>(getCurrentList()));
        }

        /**
         * How many of the channel's other members have this message on their device.
         *
         * @param message the message to process
         * @return the recipients of result
         */
        private int recipientsOf(Message message) {
            return deliveryCallback.recipientsOf(message);
        }

        protected MessageAdapter() {
            this(new MessageDeliveryCallback());
        }

        private MessageAdapter(MessageDeliveryCallback deliveryCallback) {
            super(deliveryCallback);
            this.deliveryCallback = deliveryCallback;
        }

        /**
         * Returns the item view type.
         *
         * @param position the item position in the adapter
         * @return the item view type value
         */
        @Override
        public int getItemViewType(int position) {
            Message message = getItem(position);
            if (message.getSender() != null && message.getSender().equalsIgnoreCase("You")) {
                return VIEW_TYPE_SENT;
            }
            return VIEW_TYPE_RECEIVED;
        }

        /**
         * Handles the create view holder event.
         *
         * @param parent the parent view group
         * @param viewType the adapter view type to create
         * @return the on create view holder result
         */
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_SENT) {
                ItemMessageSentBinding sentBinding = ItemMessageSentBinding.inflate(inflater, parent, false);
                return new SentMessageViewHolder(sentBinding);
            } else {
                ItemMessageReceivedBinding receivedBinding = ItemMessageReceivedBinding.inflate(inflater, parent, false);
                return new ReceivedMessageViewHolder(receivedBinding);
            }
        }

        /**
         * Handles the bind view holder event.
         *
         * @param holder the view holder to bind
         * @param position the item position in the adapter
         */
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Message message = getItem(position);
            String timeStr = dateFormat.format(new Date(message.getTimestamp()));

            if (holder instanceof SentMessageViewHolder) {
                SentMessageViewHolder sentHolder = (SentMessageViewHolder) holder;
                sentHolder.binding.textMessageBody.setText(message.getText());
                sentHolder.binding.textMessageTime.setText(timeStr);

                final boolean accepted = message.isDelivered();
                final int recipients = recipientsOf(message);
                final var context = holder.itemView.getContext();

                // A message the server has not accepted yet is greyed to match its mark, so the
                // two read as one state rather than a bright bubble claiming a send that has not
                // landed. Colour and background are set on every bind because rows are recycled.
                sentHolder.binding.textMessageBody.setTextColor(
                        androidx.core.content.ContextCompat.getColor(context,
                                accepted ? R.color.msg_text_sent : R.color.msg_text_unsent));

                // The bubble fills in as recipients pick the message up: fully transparent until
                // the first one has it, then opaque enough to read the text against, up to fully
                // solid once every other member of the channel has it. Interpolating against the
                // channel's member count rather than a fixed number is what makes a two-member
                // channel reach solid rather than crawling towards it.
                sentHolder.binding.cardMessageBubble.setCardBackgroundColor(
                        bubbleColor(context, accepted, recipients, deliveryCallback.peerCount()));

                final int statusText = recipients > 0 ? R.string.message_status_delivered
                        : accepted ? R.string.message_status_sent
                                : R.string.message_status_unsent;
                sentHolder.binding.textMessageStatus.setVisibility(View.VISIBLE);
                sentHolder.binding.textMessageStatus.setText(statusText);
                sentHolder.binding.textMessageStatus.setContentDescription(
                        context.getString(recipients > 0
                                ? R.plurals.message_status_delivered_description
                                : accepted ? R.string.message_status_sent_description
                                        : R.string.message_status_unsent_description,
                                recipients));

                // Long-pressing the bubble asks the server who has it. Set on every bind because
                // rows are recycled and the lambda captures this row's message; the bubble itself
                // is made clickable so the gesture is announced as such to a touch exploration
                // service.
                sentHolder.binding.cardMessageBubble.setClickable(true);
                sentHolder.binding.cardMessageBubble.setLongClickable(true);
                sentHolder.binding.cardMessageBubble.setOnLongClickListener(v -> {
                    if (onSentMessageLongClickListener == null) {
                        return false;
                    }
                    onSentMessageLongClickListener.onSentMessageLongClick(message);
                    return true;
                });
            } else if (holder instanceof ReceivedMessageViewHolder) {
                ReceivedMessageViewHolder receivedHolder = (ReceivedMessageViewHolder) holder;
                receivedHolder.binding.textMessageSender.setText(message.getSender());
                receivedHolder.binding.textMessageBody.setText(message.getText());
                receivedHolder.binding.textMessageTime.setText(timeStr);
            }
        }
    }

    /**
     * Represents sent message view holder data or behavior used by the application.
     *
     */
    private static class SentMessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageSentBinding binding;

        public SentMessageViewHolder(ItemMessageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    /**
     * Represents received message view holder data or behavior used by the application.
     *
     */
    private static class ReceivedMessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageReceivedBinding binding;

        public ReceivedMessageViewHolder(ItemMessageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
