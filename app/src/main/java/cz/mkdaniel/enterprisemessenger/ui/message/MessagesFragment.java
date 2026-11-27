package cz.mkdaniel.enterprisemessenger.ui.message;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

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
     */
    private final ActivityResultLauncher<String> localNetworkPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "ACCESS_LOCAL_NETWORK granted, connecting to the deployment server");
                    startConnection();
                } else {
                    Log.w(TAG, "ACCESS_LOCAL_NETWORK denied - cannot reach the deployment server");
                    updateInputUnderline(false);
                }
            });
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
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollLatestMessages();
            pollHandler.postDelayed(this, 3_000L);
        }
    };

    private void pollLatestMessages() {
        if (connectionManager == null || roomId == null || roomId.isEmpty()) {
            return;
        }
        connectionManager.fetchMessagePage(
                ServerConnectionManager.parseChannelId(roomId),
                0,
                50,
                new ServerConnectionManager.Callback<>() {
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

                    @Override
                    public void onFailure(String error) {
                        Log.d(TAG, "Poll failed: " + error);
                    }
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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
        binding.recyclerviewMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
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

    @Override
    public void onResume() {
        super.onResume();
        long channelId = ServerConnectionManager.parseChannelId(roomId);
        // Suppress push notifications for the channel being looked at and clear the pending one
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

    @Override
    public void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
        if (connectionManager != null) {
            connectionManager.setVisibleChannel(-1L);
        }
    }

    private void updateInputUnderline(boolean isConnected) {
        if (binding == null) return;
        if (isConnected) {
            binding.cardInputContainer.setStrokeColor(requireContext().getColor(R.color.outline_border));
        } else {
            binding.cardInputContainer.setStrokeColor(Color.RED);
        }
    }

    private void loadOlderMessages() {
        if (connectionManager == null || loadingOlderMessages || noMoreOlderMessages) {
            return;
        }

        loadMessagePage(historyPage, true);
    }

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
        adapter.submitList(updated, () -> {
            if (binding != null) {
                binding.recyclerviewMessages.smoothScrollToPosition(updated.size() - 1);
            }
        });

        // The server queues the encrypted message for other channel members, not back to the sender.
        connectionManager.sendChatMessage(text);

        inputField.setText("");
    }

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
        adapter.submitList(updated, () -> {
            if (binding != null) {
                binding.recyclerviewMessages.smoothScrollToPosition(updated.size() - 1);
            }
        });
        sendReadReceiptForLatest();
    }

    /**
     * Peer delivery cursors moved. The bubble's background opacity is derived at bind time from
     * these cursors rather than stored per message, so no list has to be rebuilt - the rows are
     * repainted and re-read the new state.
     */
    @Override
    public void onPeerDeliveryProgress(Map<Long, Long> cursors, int peerCount) {
        if (!isAdded() || adapter == null) {
            return;
        }
        adapter.setPeerDelivery(cursors, peerCount);
    }

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
     * <p>
     * Not yet accepted by the server: the solid bubble colour, so an unsent message is still
     * unmistakably a message. From there the bubble fades in linearly with the share of the
     * channel's other members that have it - fully transparent while nobody has pulled it,
     * completely solid once everybody has.
     * <p>
     * The ramp starts at nothing rather than at some visible minimum, so the first recipient
     * produces a small but real change instead of a jump from invisible to a fixed opacity.
     */
    private static int bubbleColor(android.content.Context context, boolean accepted,
                                   int recipients, int peerCount) {
        final int solid = androidx.core.content.ContextCompat.getColor(context,
                R.color.msg_bubble_sent);
        if (!accepted) {
            return solid;
        }
        // A channel of one other member goes solid on their first acknowledgement; a channel of
        // ten climbs one tenth of the way per recipient. Clamped in case a cursor arrives for a
        // member the listing did not include, which would otherwise divide by zero or overshoot.
        final int steps = Math.max(peerCount, 1);
        final float fraction = Math.min(1f, Math.max(0f, (float) recipients / steps));
        final int alpha = (int) (0xFF * fraction);
        return (alpha << 24) | (solid & 0x00FFFFFF);
    }

    private static class MessageAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_SENT = 1;
        private static final int VIEW_TYPE_RECEIVED = 2;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        /**
         * Each other member's user id mapped to the highest message id their client has
         * acknowledged. Counting the entries that reach a given message gives how many recipients
         * have it, which drives the bubble's opacity.
         */
        private Map<Long, Long> peerCursors = new HashMap<>();
        /**
         * How many other members the channel has. Not derivable from {@link #peerCursors}, because
         * a member who has acknowledged nothing has no entry, and would otherwise be counted as
         * having left the channel.
         */
        private int peerCount = 0;

        /**
         * Replaces the peer delivery state and repaints. A full repaint rather than a diff: the
         * cursors are not part of {@link Message}, so {@code areContentsTheSame} cannot see them
         * change, and the list is short enough that rebinding it is cheaper than threading the
         * state through the model.
         */
        void setPeerDelivery(Map<Long, Long> cursors, int peerCount) {
            this.peerCursors = new HashMap<>(cursors);
            this.peerCount = peerCount;
            notifyDataSetChanged();
        }

        /**
         * How many of the channel's other members have this message on their device.
         */
        private int recipientsOf(Message message) {
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

        protected MessageAdapter() {
            super(new DiffUtil.ItemCallback<Message>() {
                @Override
                public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
                    return oldItem.getText().equals(newItem.getText())
                            && oldItem.getSender().equals(newItem.getSender())
                            && oldItem.getTimestamp() == newItem.getTimestamp()
                            // A message the user wrote changes colour and mark when the server
                            // accepts it, so that has to count as a content change or the row
                            // never repaints from grey to normal.
                            && oldItem.isDelivered() == newItem.isDelivered()
                            && oldItem.isPeerDelivered() == newItem.isPeerDelivered();
                }
            });
        }

        @Override
        public int getItemViewType(int position) {
            Message message = getItem(position);
            if (message.getSender() != null && message.getSender().equalsIgnoreCase("You")) {
                return VIEW_TYPE_SENT;
            }
            return VIEW_TYPE_RECEIVED;
        }

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
                        bubbleColor(context, accepted, recipients, peerCount));

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
            } else if (holder instanceof ReceivedMessageViewHolder) {
                ReceivedMessageViewHolder receivedHolder = (ReceivedMessageViewHolder) holder;
                receivedHolder.binding.textMessageSender.setText(message.getSender());
                receivedHolder.binding.textMessageBody.setText(message.getText());
                receivedHolder.binding.textMessageTime.setText(timeStr);
            }
        }
    }

    private static class SentMessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageSentBinding binding;

        public SentMessageViewHolder(ItemMessageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class ReceivedMessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageReceivedBinding binding;

        public ReceivedMessageViewHolder(ItemMessageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
