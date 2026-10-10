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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentMessagesBinding;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

/**
 * Chat screen showing messages in a room.
 * Communicates with the remote server via WebSocket using ServerConnectionManager.
 * Displays a red underline on the message input field if the server cannot be reached.
 */
public class MessagesFragment extends Fragment implements ServerConnectionManager.ConnectionListener {

    private static final String TAG = "MessagesFragment";

    private FragmentMessagesBinding binding;
    private MessageAdapter adapter;
    private ServerConnectionManager connectionManager;

    private ColorStateList defaultInputTint;
    private ColorStateList redInputTint;

    private String serverIp;
    private String roomId;
    private String roomName;
    private String encryptionKey;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // --- Arguments setup (server IP & channel encryption key) ---
        if (getArguments() != null) {
            serverIp = getArguments().getString("serverIp", "");
            roomId = getArguments().getString("roomId", "");
            roomName = getArguments().getString("roomName", "");
            encryptionKey = getArguments().getString("encryptionKey", "");
        }

        Log.d(TAG, "Opening MessagesFragment for serverIp=" + serverIp
                + ", room=" + roomName + " (" + roomId + ")"
                + ", encryptionKey=" + encryptionKey);

        // --- Underline tint setup ---
        defaultInputTint = binding.edittextMessageInput.getBackgroundTintList();
        redInputTint = ColorStateList.valueOf(Color.RED);

        // --- RecyclerView setup ---
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        adapter = new MessageAdapter();

        binding.recyclerviewMessages.setLayoutManager(layoutManager);
        binding.recyclerviewMessages.setAdapter(adapter);

        // Initial messages
        List<Message> initialMessages = generatePlaceholderMessages();
        adapter.submitList(initialMessages);

        // --- Server Connection setup ---
        connectionManager = ServerConnectionManager.getInstance(requireContext());
        connectionManager.addListener(this);

        if (serverIp != null && !serverIp.isEmpty()) {
            connectionManager.connectToServer(serverIp);
            connectionManager.joinRoom(roomId);
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

    private void updateInputUnderline(boolean isConnected) {
        if (binding == null) return;
        if (isConnected) {
            binding.edittextMessageInput.setBackgroundTintList(defaultInputTint);
        } else {
            binding.edittextMessageInput.setBackgroundTintList(redInputTint);
        }
    }

    private void sendMessageFromInput(EditText inputField) {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        if (!connectionManager.isConnected()) {
            updateInputUnderline(false);
            Toast.makeText(requireContext(), R.string.error_server_unreachable, Toast.LENGTH_SHORT).show();
            return;
        }

        Message sent = new Message(UUID.randomUUID().toString(), text, "You", System.currentTimeMillis());
        List<Message> updated = new ArrayList<>(adapter.getCurrentList());
        updated.add(sent);
        adapter.submitList(updated, () -> {
            if (binding != null) {
                binding.recyclerviewMessages.smoothScrollToPosition(updated.size() - 1);
            }
        });

        // Transmit over WebSocket
        byte[] keyBytes = (encryptionKey != null && !encryptionKey.isEmpty())
                ? encryptionKey.getBytes(StandardCharsets.UTF_8)
                : null;
        connectionManager.sendChatMessage(text, "You", keyBytes);

        inputField.setText("");
    }

    @Override
    public void onConnectionStateChanged(boolean connected, String ip) {
        if (!isAdded()) return;
        Log.d(TAG, "Connection state changed: connected=" + connected + ", ip=" + ip);
        updateInputUnderline(connected);
        if (connected) {
            Toast.makeText(getContext(), "Connected to server: " + ip, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), R.string.error_server_unreachable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMessageReceived(Message message) {
        if (!isAdded()) return;
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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (connectionManager != null) {
            connectionManager.removeListener(this);
        }
        binding = null;
    }

    private List<Message> generatePlaceholderMessages() {
        long now = System.currentTimeMillis();
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String sender = (i % 2 == 0) ? "Alice" : "Bob";
            messages.add(new Message(
                    UUID.randomUUID().toString(),
                    "Welcome to #" + (roomName != null ? roomName : "channel") + "! Message " + i,
                    sender,
                    now - (5L - i) * 60_000L
            ));
        }
        return messages;
    }

    // ---------- adapter & view holder ----------

    private static class MessageAdapter extends ListAdapter<Message, MessageViewHolder> {

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

        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new MessageViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            Message message = getItem(position);
            holder.text1.setText(message.getSender());
            holder.text2.setText(message.getText());
        }
    }

    private static class MessageViewHolder extends RecyclerView.ViewHolder {

        private final TextView text1;
        private final TextView text2;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
            text2 = itemView.findViewById(android.R.id.text2);
        }
    }
}
