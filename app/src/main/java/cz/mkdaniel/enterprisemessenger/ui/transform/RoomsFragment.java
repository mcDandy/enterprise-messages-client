package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.os.Bundle;
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

import java.util.Arrays;
import java.util.List;

import cz.mkdaniel.enterprisemessenger.R;

/**
 * Displays rooms/channels for the selected server.
 */
public class RoomsFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        // Get the selected server index and IP address passed from TransformFragment
        int serverIndex = getArguments() != null ? getArguments().getInt("serverIndex", 0) : 0;
        String serverIp = getArguments() != null ? getArguments().getString("serverIp", "127.0.0.1") : "127.0.0.1";

        RecyclerView recyclerView = new RecyclerView(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setId(View.generateViewId());

        ListAdapter<Room, RoomViewHolder> adapter = new RoomsAdapter(
                room -> {
                    // Navigate to the Messages screen for this room with encryption key and server IP
                    Bundle args = new Bundle();
                    args.putInt("serverIndex", serverIndex);
                    args.putString("serverIp", serverIp);
                    args.putString("roomId", room.getId());
                    args.putString("roomName", room.getName());
                    args.putString("encryptionKey", room.getEncryptionKey());
                    NavController navController = NavHostFragment.findNavController(this);
                    navController.navigate(R.id.nav_messages, args);
                }
        );
        recyclerView.setAdapter(adapter);

        // Placeholder rooms with encryption keys — will be replaced by server data later
        List<Room> placeholderRooms = Arrays.asList(
                new Room("r1", "general", "key_aes256_gen_01"),
                new Room("r2", "random", "key_aes256_rnd_02"),
                new Room("r3", "announcements", "key_aes256_ann_03"),
                new Room("r4", "development", "key_aes256_dev_04"),
                new Room("r5", "design", "key_aes256_dsg_05"),
                new Room("r6", "support", "key_aes256_spt_06")
        );
        adapter.submitList(placeholderRooms);

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
                            && oldItem.getEncryptionKey().equals(newItem.getEncryptionKey());
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
