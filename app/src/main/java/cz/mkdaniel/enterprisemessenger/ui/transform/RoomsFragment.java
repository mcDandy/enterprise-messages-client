package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

/**
 * Displays rooms/channels for the selected server.
 * Channels with unread messages are displayed with bold font.
 */
public class RoomsFragment extends Fragment {

    private static final String TAG = "RoomsFragment";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        // Get the selected server index, name, and IP address passed from TransformFragment
        int serverIndex = getArguments() != null ? getArguments().getInt("serverIndex", 0) : 0;
        String serverName = getArguments() != null ? getArguments().getString("serverName", "Server") : "Server";
        String defaultIp = getString(R.string.server_ip);
        String serverIp = getArguments() != null ? getArguments().getString("serverIp", defaultIp) : defaultIp;

        RecyclerView recyclerView = new RecyclerView(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, 12, 0, 12);
        recyclerView.setId(View.generateViewId());

        ListAdapter<Room, RoomViewHolder> adapter = new RoomsAdapter(
                room -> {
                    // When clicked, clear new messages indicator
                    room.setHasNewMessages(false);

                    // Navigate to the Messages screen for this room with encryption key and server IP
                    Bundle args = new Bundle();
                    args.putInt("serverIndex", serverIndex);
                    args.putString("serverName", serverName);
                    args.putString("serverIp", serverIp);
                    args.putString("roomId", room.getId());
                    args.putString("roomName", room.getName());
                    args.putString("encryptionKey", room.getEncryptionKey());
                    NavController navController = NavHostFragment.findNavController(this);
                    navController.navigate(R.id.action_rooms_to_messages, args);
                }
        );
        recyclerView.setAdapter(adapter);

        ServerConnectionManager.getInstance(requireContext()).fetchChannels(serverIp,
                new ServerConnectionManager.Callback<List<Room>>() {
                    @Override
                    public void onResult(List<Room> rooms) {
                        Log.d(TAG, "Loaded " + rooms.size() + " channels from " + serverIp);
                        adapter.submitList(rooms);
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.w(TAG, "Failed to load channel list: " + error);
                    }
                });

        return recyclerView;
    }

    private static class RoomsAdapter extends ListAdapter<Room, RoomViewHolder> {

        @NonNull
        private final OnRoomClickListener clickListener;

        protected RoomsAdapter(@NonNull OnRoomClickListener clickListener) {
            super(new DiffUtil.ItemCallback<Room>() {
                @Override
                public boolean areItemsTheSame(@NonNull Room oldItem, @NonNull Room newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Room oldItem, @NonNull Room newItem) {
                    return oldItem.getName().equals(newItem.getName())
                            && oldItem.getEncryptionKey().equals(newItem.getEncryptionKey())
                            && oldItem.hasNewMessages() == newItem.hasNewMessages();
                }
            });
            this.clickListener = clickListener;
        }

        @NonNull
        @Override
        public RoomViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View itemView = inflater.inflate(R.layout.item_room, parent, false);
            return new RoomViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull RoomViewHolder holder, int position) {
            Room room = getItem(position);
            holder.roomNameText.setText(room.getName());

            // Set bold font if channel has new unread messages
            if (room.hasNewMessages()) {
                holder.roomNameText.setTypeface(null, Typeface.BOLD);
            } else {
                holder.roomNameText.setTypeface(null, Typeface.NORMAL);
            }

            holder.itemView.setOnClickListener(v -> clickListener.onRoomClicked(room));
        }
    }

    private static class RoomViewHolder extends RecyclerView.ViewHolder {

        private final TextView roomNameText;

        public RoomViewHolder(@NonNull View itemView) {
            super(itemView);
            roomNameText = itemView.findViewById(R.id.textview_room_name);
        }
    }

    private interface OnRoomClickListener {
        void onRoomClicked(Room room);
    }
}
