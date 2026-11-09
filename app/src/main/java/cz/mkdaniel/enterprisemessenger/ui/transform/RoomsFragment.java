package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentRoomsBinding;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;
import cz.mkdaniel.enterprisemessenger.ui.server.ServerNameDialogFragment;

/**
 * Displays the channels of one virtual server, i.e. {@code GET /api/servers/{id}/channels/my}.
 * Channels with unread messages are displayed with bold font.
 * <p>
 * The FAB and the rename/delete entries of the long-press menu are only shown when the parent
 * virtual server's {@code isAdminServer} argument is true, because the server restricts channel
 * management to that server's administrator. It enforces the rule again with 403, which is reported
 * as a toast rather than treated as a broken session.
 */
public class RoomsFragment extends Fragment {

    private static final String TAG = "RoomsFragment";

    private FragmentRoomsBinding binding;
    private RoomAdapter adapter;

    private long serverId;
    private boolean isAdminServer;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentRoomsBinding.inflate(inflater, container, false);

        serverId = getArguments() != null ? getArguments().getLong("serverId", -1L) : -1L;
        isAdminServer = getArguments() != null && getArguments().getBoolean("isAdminServer", false);

        adapter = new RoomAdapter(new OnRoomClickListener() {
            @Override
            public void onRoomClicked(Room room) {
                // When clicked, clear new messages indicator
                room.setHasNewMessages(false);

                // Navigate to the Messages screen for this room; no address travels with it, the
                // physical server is fixed, only the channel is selected here.
                Bundle args = new Bundle();
                args.putLong("serverId", serverId);
                args.putString("roomId", room.getId());
                args.putString("roomName", room.getName());
                NavController navController = NavHostFragment.findNavController(RoomsFragment.this);
                navController.navigate(R.id.action_rooms_to_messages, args);
            }

            @Override
            public void onRoomLongClicked(Room room) {
                showChannelOptionsDialog(room);
            }
        });

        binding.recyclerviewRooms.setAdapter(adapter);

        // Only the virtual server's administrator may manage its channels.
        binding.fabAddChannel.setVisibility(isAdminServer ? View.VISIBLE : View.GONE);
        binding.fabAddChannel.setOnClickListener(v -> showChannelNameDialog(
                R.string.dialog_add_channel_title, R.string.hint_channel_name,
                R.string.action_add, null));

        loadChannels();

        return binding.getRoot();
    }

    private void loadChannels() {
        ServerConnectionManager.getInstance(requireContext()).fetchChannels(serverId,
                new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(List<Room> rooms) {
                        if (binding == null) return;
                        Log.d(TAG, "Loaded " + rooms.size() + " channels of virtual server " + serverId);
                        adapter.submitList(rooms);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (binding == null) return;
                        Log.w(TAG, "Failed to load channel list: " + error);
                        toast(error);
                    }
                });
    }

    /**
     * Long-press menu. Channels exist to be opened by everyone, so only administrators get the
     * rename and delete entries.
     */
    private void showChannelOptionsDialog(Room room) {
        if (!isAdminServer) {
            return;
        }

        final String renameLabel = getString(R.string.action_rename);
        final String deleteLabel = getString(R.string.action_delete);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(room.getName())
                .setItems(new String[]{renameLabel, deleteLabel}, (dialog, which) -> {
                    if (which == 0) {
                        showChannelNameDialog(R.string.dialog_edit_channel_title, R.string.hint_channel_name,
                                R.string.action_save, room);
                    } else {
                        showDeleteConfirmationDialog(room);
                    }
                })
                .show();
    }

    /**
     * Name dialog for both flows: {@code existing == null} creates a channel in this virtual
     * server, otherwise the name replaces that of {@code existing}.
     */
    private void showChannelNameDialog(int titleRes, int hintRes, int positiveButtonRes,
                                       @Nullable Room existing) {
        ServerNameDialogFragment dialog = ServerNameDialogFragment.newInstance(
                titleRes, hintRes, positiveButtonRes, existing == null ? "" : existing.getName());
        dialog.setOnNameConfirmed(name -> {
            ServerConnectionManager manager = ServerConnectionManager.getInstance(requireContext());

            if (existing == null) {
                manager.createChannel(serverId, name, new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(Room created) {
                        loadChannels();
                    }

                    @Override
                    public void onFailure(String error) {
                        toast(error);
                    }
                });
            } else {
                final long channelId = ServerConnectionManager.parseChannelId(existing.getId());

                manager.renameChannel(channelId, name, new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(Void result) {
                        loadChannels();
                    }

                    @Override
                    public void onFailure(String error) {
                        toast(error);
                    }
                });
            }
        });
        dialog.show(getParentFragmentManager(), "channel_name_dialog");
    }

    private void showDeleteConfirmationDialog(Room room) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_channel_title)
                .setMessage(getString(R.string.dialog_delete_channel_message, room.getName()))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    ServerConnectionManager.getInstance(requireContext())
                            .deleteChannel(ServerConnectionManager.parseChannelId(room.getId()),
                                    new ServerConnectionManager.Callback<>() {
                                        @Override
                                        public void onResult(Void result) {
                                            loadChannels();
                                        }

                                        @Override
                                        public void onFailure(String error) {
                                            toast(error);
                                        }
                                    });
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void toast(String message) {
        if (message == null || message.isEmpty() || !isAdded()) return;
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        adapter = null;
    }

    private static class RoomAdapter extends ListAdapter<Room, RoomViewHolder> {

        @NonNull
        private final OnRoomClickListener clickListener;

        protected RoomAdapter(@NonNull OnRoomClickListener clickListener) {
            super(new DiffUtil.ItemCallback<Room>() {
                @Override
                public boolean areItemsTheSame(@NonNull Room oldItem, @NonNull Room newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Room oldItem, @NonNull Room newItem) {
                    return oldItem.getId().equals(newItem.getId())
                            && oldItem.getName().equals(newItem.getName())
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

            holder.itemView.setOnLongClickListener(v -> {
                clickListener.onRoomLongClicked(room);
                return true;
            });
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

        void onRoomLongClicked(Room room);
    }
}
