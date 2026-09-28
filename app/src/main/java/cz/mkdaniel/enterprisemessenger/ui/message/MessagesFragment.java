package cz.mkdaniel.enterprisemessenger.ui.message;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.databinding.FragmentMessagesBinding;

/**
 * Chat screen showing messages in a room.
 *
 * Features:
 * - Paginated message loading (triggered when scrolling near the top)
 * - Text input field at the bottom
 * - Circular send button on the right of the text field
 */
public class MessagesFragment extends Fragment {

    private FragmentMessagesBinding binding;
    private MessageAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // --- RecyclerView setup ---
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        adapter = new MessageAdapter();

        binding.recyclerviewMessages.setLayoutManager(layoutManager);
        binding.recyclerviewMessages.setAdapter(adapter);

        // Placeholder messages — will be fetched from server later
        List<Message> placeholderMessages = generatePlaceholderMessages();
        adapter.submitList(placeholderMessages);

        // --- Pagination: load older messages when scrolled near the top ---
        binding.recyclerviewMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (layoutManager.findFirstVisibleItemPosition() <= 2 && !adapter.isLoading()) {
                    // TODO: Fetch older messages from server and prepend to the list.
                    //       Use a MutableLiveData / StateFlow in a ViewModel,
                    //       then call adapter.submitList(newList) with combined results.
                }
            }
        });

        // --- Send button & text field ---
        ImageButton sendButton = binding.buttonSendMessage;
        EditText inputField = binding.edittextMessageInput;

        sendButton.setOnClickListener(v -> {
            String text = inputField.getText().toString().trim();
            if (!text.isEmpty()) {
                // TODO: Post the message to the server via ViewModel/repository.
                //
                // For now, echo it back into the local list so the user sees
                // immediate feedback while waiting for server round-trip.
                Message sent = new Message(UUID.randomUUID().toString(), text, "You", System.currentTimeMillis());
                List<Message> updated = new ArrayList<>(adapter.getCurrentList());
                updated.add(sent);
                adapter.submitList(updated);

                inputField.setText("");
            }
        });

        // Also send when pressing Enter / action IME
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            String text = v.getText().toString().trim();
            if (!text.isEmpty()) {
                Message sent = new Message(UUID.randomUUID().toString(), text, "You", System.currentTimeMillis());
                List<Message> updated = new ArrayList<>(adapter.getCurrentList());
                updated.add(sent);
                adapter.submitList(updated);
                v.setText("");
            }
            return true;
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ---------- placeholder data (remove when server exists) ----------

    private List<Message> generatePlaceholderMessages() {
        long now = System.currentTimeMillis();
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String sender = (i % 3 == 0) ? "Alice" : ((i % 3 == 1) ? "Bob" : "Charlie");
            messages.add(new Message(
                    UUID.randomUUID().toString(),
                    "This is placeholder message #" + i,
                    sender,
                    now - (20L - i) * 60_000L
            ));
        }
        return messages;
    }

    // ---------- adapter & view holder ----------

    private static class MessageAdapter extends ListAdapter<Message, MessageViewHolder> {

        private boolean isLoading = false;

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

        public boolean isLoading() {
            return isLoading;
        }

        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // For now inflate a simple layout programmatically; replace with R.layout.item_message later.
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
            // Using android.R.layout.simple_list_item_2 — it has two text fields
            text1 = itemView.findViewById(android.R.id.text1);
            text2 = itemView.findViewById(android.R.id.text2);
        }
    }
}
