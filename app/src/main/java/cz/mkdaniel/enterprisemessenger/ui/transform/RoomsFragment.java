package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

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
        binding = FragmentRoomsBinding.inflate(inflater, container, false);

        serverId = getArguments() != null ? getArguments().getLong("serverId", -1L) : -1L;
        isAdminServer = getArguments() != null && getArguments().getBoolean("isAdminServer", false);

        adapter = new RoomAdapter(new OnRoomClickListener() {
            /**
             * Handles selection of a room.
             *
             * @param room the room selected by the user
             */
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

            /**
             * Handles a long press on a room.
             *
             * @param room the room selected by the user
             */
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

    /**
     * Loads the channels.
     *
     */
    private void loadChannels() {
        ServerConnectionManager.getInstance(requireContext()).fetchChannels(serverId,
                new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param rooms the rooms associated with this callback
                     */
                    @Override
                    public void onResult(List<Room> rooms) {
                        if (binding == null) return;
                        Log.d(TAG, "Loaded " + rooms.size() + " channels of virtual server " + serverId);
                        adapter.submitList(rooms);
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
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
     *
     * @param room the room whose details or messages are being handled
     */
    private void showChannelOptionsDialog(Room room) {
        if (!isAdminServer) {
            return;
        }

        final String renameLabel = getString(R.string.action_rename);
        final String deleteLabel = getString(R.string.action_delete);
        final String addMemberLabel = getString(R.string.action_add_channel_member);
        final String removeMemberLabel = getString(R.string.action_remove_channel_member);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(room.getName())
                .setItems(new String[]{addMemberLabel, removeMemberLabel, renameLabel, deleteLabel},
                        (dialog, which) -> {
                            if (which == 0) {
                                showAddChannelMemberDialog(room);
                            } else if (which == 1) {
                                showRemoveChannelMemberDialog(room);
                            } else if (which == 2) {
                                showChannelNameDialog(R.string.dialog_edit_channel_title, R.string.hint_channel_name,
                                        R.string.action_save, room);
                            } else {
                                showDeleteConfirmationDialog(room);
                            }
                        })
                .show();
    }

    /**
     * Member picker for removal. The server's channel member list carries nothing but user ids, so
     * that is all the list can show. Removal is also what revokes read access: the server responds by
     * handing every remaining member a new channel key.
     *
     * @param room the room whose details or messages are being handled
     */
    private void showRemoveChannelMemberDialog(Room room) {
        final long channelId = ServerConnectionManager.parseChannelId(room.getId());

        ServerConnectionManager.getInstance(requireContext()).fetchChannelMembers(channelId,
                new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param members the channel or server members returned by the request
                     */
                    @Override
                    public void onResult(JSONArray members) {
                        if (binding == null) return;

                        final String[] labels = new String[members.length()];
                        final long[] userIds = new long[members.length()];
                        for (int i = 0; i < members.length(); i++) {
                            final JSONObject member = members.optJSONObject(i);
                            userIds[i] = member == null ? -1L : member.optLong("userId", -1L);
                            labels[i] = "User ID: " + userIds[i];
                        }

                        if (labels.length == 0) {
                            toast(getString(R.string.channel_no_members));
                            return;
                        }

                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.dialog_remove_channel_member_title)
                                .setItems(labels, (dialog, which) ->
                                        confirmRemoveChannelMember(channelId, userIds[which]))
                                .show();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        if (binding == null) return;
                        Log.w(TAG, "Failed to load the member list of channel " + channelId + ": " + error);
                        toast(error);
                    }
                });
    }

    /**
     * Removal is irreversible for the removed device's history access, so it is confirmed before the
     * server is asked to re-key the channel.
     *
     * @param channelId the identifier of the channel
     * @param userId the identifier of the user
     */
    private void confirmRemoveChannelMember(long channelId, long userId) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_remove_channel_member_title)
                .setMessage(getString(R.string.dialog_remove_channel_member_message, userId))
                .setPositiveButton(R.string.action_remove, (dialog, which) ->
                        ServerConnectionManager.getInstance(requireContext()).removeChannelMember(
                                channelId, userId,
                                new ServerConnectionManager.Callback<>() {
                                    /**
                                     * Handles the successful result of the asynchronous operation.
                                     *
                                     * @param result the result produced by the asynchronous operation
                                     */
                                    @Override
                                    public void onResult(Void result) {
                                        toast(getString(R.string.channel_member_removed));
                                    }

                                    /**
                                     * Handles a failure from the asynchronous operation.
                                     *
                                     * @param error the error message explaining the failure
                                     */
                                    @Override
                                    public void onFailure(String error) {
                                        toast(error);
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Shows the add channel member dialog.
     *
     * @param room the room whose details or messages are being handled
     */
    private void showAddChannelMemberDialog(Room room) {
        final EditText input = new EditText(requireContext());
        input.setHint(R.string.hint_member_user_id);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_add_channel_member_title)
                .setView(input)
                .setPositiveButton(R.string.action_add, (dialog, which) -> {
                    final long userId;
                    try {
                        userId = Long.parseLong(input.getText().toString().trim());
                    } catch (NumberFormatException exception) {
                        toast(getString(R.string.error_invalid_member_user_id));
                        return;
                    }

                    ServerConnectionManager.getInstance(requireContext()).addChannelMember(
                            ServerConnectionManager.parseChannelId(room.getId()), userId,
                            new ServerConnectionManager.Callback<>() {
                                /**
                                 * Handles the successful result of the asynchronous operation.
                                 *
                                 * @param result the result produced by the asynchronous operation
                                 */
                                @Override
                                public void onResult(Void result) {
                                    toast(getString(R.string.channel_member_added));
                                }

                                /**
                                 * Handles a failure from the asynchronous operation.
                                 *
                                 * @param error the error message explaining the failure
                                 */
                                @Override
                                public void onFailure(String error) {
                                    toast(error);
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Name dialog for both flows: {@code existing == null} creates a channel in this virtual
     * server, otherwise the name replaces that of {@code existing}.
     *
     * @param titleRes the string resource ID for the dialog title
     * @param hintRes the string resource ID for the input hint
     * @param positiveButtonRes the string resource ID for the positive button
     * @param existing the existing value or entry
     */
    private void showChannelNameDialog(int titleRes, int hintRes, int positiveButtonRes,
                                       @Nullable Room existing) {
        ServerNameDialogFragment dialog = ServerNameDialogFragment.newInstance(
                titleRes, hintRes, positiveButtonRes, existing == null ? "" : existing.getName());
        dialog.setOnNameConfirmed(name -> {
            ServerConnectionManager manager = ServerConnectionManager.getInstance(requireContext());

            if (existing == null) {
                manager.createChannel(serverId, name, new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param created the newly created room
                     */
                    @Override
                    public void onResult(Room created) {
                        loadChannels();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        toast(error);
                    }
                });
            } else {
                final long channelId = ServerConnectionManager.parseChannelId(existing.getId());

                manager.renameChannel(channelId, name, new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param result the result produced by the asynchronous operation
                     */
                    @Override
                    public void onResult(Void result) {
                        loadChannels();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        toast(error);
                    }
                });
            }
        });
        dialog.show(getParentFragmentManager(), "channel_name_dialog");
    }

    /**
     * Shows the delete confirmation dialog.
     *
     * @param room the room whose details or messages are being handled
     */
    private void showDeleteConfirmationDialog(Room room) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_channel_title)
                .setMessage(getString(R.string.dialog_delete_channel_message, room.getName()))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    ServerConnectionManager.getInstance(requireContext())
                            .deleteChannel(ServerConnectionManager.parseChannelId(room.getId()),
                                    new ServerConnectionManager.Callback<>() {
                                        /**
                                         * Handles the successful result of the asynchronous operation.
                                         *
                                         * @param result the result produced by the asynchronous operation
                                         */
                                        @Override
                                        public void onResult(Void result) {
                                            loadChannels();
                                        }

                                        /**
                                         * Handles a failure from the asynchronous operation.
                                         *
                                         * @param error the error message explaining the failure
                                         */
                                        @Override
                                        public void onFailure(String error) {
                                            toast(error);
                                        }
                                    });
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Converts the value to ast.
     *
     * @param message the message to process
     */
    private void toast(String message) {
        if (message == null || message.isEmpty() || !isAdded()) return;
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    /**
     * Handles the destroy view event.
     *
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        adapter = null;
    }

    /**
     * Defines the contract for on room click listener.
     *
     */
    private interface OnRoomClickListener {
        /**
         * Handles the room clicked event.
         *
         * @param room the room whose details or messages are being handled
         */
        void onRoomClicked(Room room);

        /**
         * Handles the room long clicked event.
         *
         * @param room the room whose details or messages are being handled
         */
        void onRoomLongClicked(Room room);
    }

    /**
     * Represents room adapter data or behavior used by the application.
     *
     */
    private static class RoomAdapter extends ListAdapter<Room, RoomViewHolder> {

        @NonNull
        private final OnRoomClickListener clickListener;

        protected RoomAdapter(@NonNull OnRoomClickListener clickListener) {
            super(new DiffUtil.ItemCallback<Room>() {
                /**
                 * Compares item identifiers to determine whether two list entries represent the same item.
                 *
                 * @param oldItem the earlier list item being compared
                 * @param newItem the updated list item being compared
                 * @return whether the operation succeeded
                 */
                @Override
                public boolean areItemsTheSame(@NonNull Room oldItem, @NonNull Room newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                /**
                 * Compares item contents to determine whether a list entry needs rebinding.
                 *
                 * @param oldItem the earlier list item being compared
                 * @param newItem the updated list item being compared
                 * @return whether the operation succeeded
                 */
                @Override
                public boolean areContentsTheSame(@NonNull Room oldItem, @NonNull Room newItem) {
                    return oldItem.getId().equals(newItem.getId())
                            && oldItem.getName().equals(newItem.getName())
                            && oldItem.hasNewMessages() == newItem.hasNewMessages();
                }
            });
            this.clickListener = clickListener;
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
        public RoomViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View itemView = inflater.inflate(R.layout.item_room, parent, false);
            return new RoomViewHolder(itemView);
        }

        /**
         * Handles the bind view holder event.
         *
         * @param holder the view holder to bind
         * @param position the item position in the adapter
         */
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

    /**
     * Represents room view holder data or behavior used by the application.
     *
     */
    private static class RoomViewHolder extends RecyclerView.ViewHolder {

        private final TextView roomNameText;

        public RoomViewHolder(@NonNull View itemView) {
            super(itemView);
            roomNameText = itemView.findViewById(R.id.textview_room_name);
        }
    }
}
