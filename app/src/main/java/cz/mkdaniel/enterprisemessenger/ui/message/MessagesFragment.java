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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentMessagesBinding;
import cz.mkdaniel.enterprisemessenger.databinding.ItemMessageReceivedBinding;
import cz.mkdaniel.enterprisemessenger.databinding.ItemMessageSentBinding;
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
        }
        NotificationHelper.cancelNotificationForChannel(requireContext(), channelId);
    }

    @Override
    public void onPause() {
        super.onPause();
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

                        final List<Message> merged = new ArrayList<>(pageMessages);
                        for (Message liveMessage : adapter.getCurrentList()) {
                            boolean alreadyPresent = false;
                            for (Message message : merged) {
                                if (message.getId().equals(liveMessage.getId())) {
                                    alreadyPresent = true;
                                    break;
                                }
                            }
                            if (!alreadyPresent) {
                                merged.add(liveMessage);
                            }
                        }
                        merged.sort(java.util.Comparator.comparingLong(Message::getTimestamp));

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

    private void sendMessageFromInput(EditText inputField) {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        if (!connectionManager.isConnected()) {
            updateInputUnderline(false);
            Toast.makeText(requireContext(), R.string.error_server_unreachable, Toast.LENGTH_SHORT).show();
            return;
        }

        String messageId = UUID.randomUUID().toString();
        Message sent = new Message(messageId, text, "You", System.currentTimeMillis());
        List<Message> updated = new ArrayList<>(adapter.getCurrentList());
        updated.add(sent);
        adapter.submitList(updated, () -> {
            if (binding != null) {
                binding.recyclerviewMessages.smoothScrollToPosition(updated.size() - 1);
            }
        });

        // Encrypt with the active channel key and transmit over WebSocket. The server broadcasts
        // the ciphertext back to the whole channel including us; onMessageReceived below drops
        // that echo because the id inside the envelope matches the bubble added above.
        String username = new cz.mkdaniel.enterprisemessenger.net.AccountStore(requireContext()).getUsername();
        connectionManager.sendChatMessage(messageId, text, username == null || username.isEmpty() ? "You" : username);

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
        // The server broadcasts to the whole channel including the sender - skip our own echo
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
        if (connectionManager != null) {
            connectionManager.removeListener(this);
        }
        binding = null;
    }

    // ---------- adapter & view holder ----------

    private static class MessageAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_SENT = 1;
        private static final int VIEW_TYPE_RECEIVED = 2;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

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
                            && oldItem.getTimestamp() == newItem.getTimestamp();
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
