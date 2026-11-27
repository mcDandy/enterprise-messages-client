package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentTransformBinding;
import cz.mkdaniel.enterprisemessenger.databinding.ItemTransformBinding;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;
import cz.mkdaniel.enterprisemessenger.notification.NotificationHelper;
import cz.mkdaniel.enterprisemessenger.ui.server.ServerNameDialogFragment;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shows the <b>virtual</b> servers the signed-in user belongs to. They come from the one physical
 * server (address from {@code @string/server_ip}) over REST, so nothing here knows an address and
 * nothing is cached on the device. The number of columns auto-fits to the available width: each
 * cell is at least {@code @dimen/server_item_min_width} wide.
 * <p>
 * The FAB creates a virtual server. Long press offers switching, plus rename and delete, but the
 * latter two only when {@link ServerViewItem#isAdmin()} says the user administers that server; the
 * server enforces the same rule and answers 403, which is surfaced as a toast either way.
 */
public class TransformFragment extends Fragment {

    private static final String TAG = "TransformGrid";

    private FragmentTransformBinding binding;
    private TransformViewModel transformViewModel;

    /**
     * Handles the create view event.
     *
     * @param inflater the layout inflater used to create the view
     * @param container the parent view that will contain the created view
     * @param savedInstanceState the saved instance state to restore, or null when creating the screen for the first time
     * @return the on create view result
     */
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        transformViewModel = new ViewModelProvider(requireActivity()).get(TransformViewModel.class);

        binding = FragmentTransformBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        RecyclerView recyclerView = binding.recyclerviewTransform;

        final int minItemWidthPx = (int) getResources().getDimension(R.dimen.server_item_min_width);
        final int horizontalMarginPx = (int) getResources().getDimension(R.dimen.fragment_horizontal_margin);

        // 1. Initial estimate from display metrics so items render in multi-column immediately
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int approxWidthPx = dm.widthPixels - (2 * horizontalMarginPx);
        int initialColumns = Math.max(1, approxWidthPx / minItemWidthPx);

        Log.d(TAG, "onCreateView: displayWidth=" + dm.widthPixels
                + ", approxWidthPx=" + approxWidthPx
                + ", minItemWidthPx=" + minItemWidthPx
                + ", initialColumns=" + initialColumns);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(requireContext(), initialColumns);
        recyclerView.setLayoutManager(gridLayoutManager);

        // 2. Exact adjustment after layout measurement (accounts for notch, insets, margins, desktop resizing).
        recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
            int widthPx = right - left;
            if (widthPx <= 0 || minItemWidthPx <= 0) return;

            int exactColumns = Math.max(1, widthPx / minItemWidthPx);
            if (gridLayoutManager.getSpanCount() != exactColumns) {
                Log.d(TAG, "onLayoutChange: widthPx=" + widthPx
                        + ", updating spanCount from " + gridLayoutManager.getSpanCount()
                        + " to " + exactColumns);
                v.post(() -> {
                    if (binding != null && isAdded()) {
                        gridLayoutManager.setSpanCount(exactColumns);
                    }
                });
            }
        });

        ListAdapter<ServerViewItem, TransformViewHolder> adapter =
                new TransformAdapter(new OnServerClickListener() {
                    /**
                     * Handles selection of a server.
                     *
                     * @param server the server selected by the user
                     */
                    @Override
                    public void onServerClicked(ServerViewItem server) {
                        // Cancel notification for this server when user opens it
                        NotificationHelper.cancelNotificationForServer(requireContext(), server.getText());
                        openServer(server);
                    }

                    /**
                     * Handles a long press on a server.
                     *
                     * @param item the item selected by the user
                     */
                    @Override
                    public void onServerLongClicked(ServerViewItem item) {
                        showServerOptionsDialog(item);
                    }
                });
        recyclerView.setAdapter(adapter);

        binding.fabAddServer.setOnClickListener(v -> showServerNameDialog(
                R.string.dialog_create_server_title, R.string.hint_server_name,
                R.string.action_add, null));

        transformViewModel.getItems().observe(getViewLifecycleOwner(), items -> {
            adapter.submitList(items);

            // Trigger system notifications for servers with unread messages (showing number of messages & server)
            if (items != null) {
                for (ServerViewItem item : items) {
                    if (item.hasUnread()) {
                        NotificationHelper.showMessageNotification(requireContext(), item.getText(), item.getUnreadCount());
                    }
                }
            }
        });

        // A rejected request - typically 403 from a mutation the server's role check refused.
        transformViewModel.getErrorMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null || message.isEmpty()) {
                return;
            }
            if (isAdded()) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
            transformViewModel.clearErrorMessage();
        });

        return root;
    }

    /**
     * Long-press menu. Rename and delete are only offered to the virtual server's administrator -
     * see {@link ServerViewItem#isAdmin()}, which mirrors the server-side membership check.
     *
     * @param item the item displayed by this row
     */
    private void showServerOptionsDialog(ServerViewItem item) {
        final String switchLabel = getString(R.string.action_switch_server);
        final String renameLabel = getString(R.string.action_rename);
        final String deleteLabel = getString(R.string.action_delete);
        final String addMemberLabel = getString(R.string.action_add_member);
        final String adminPanelLabel = getString(R.string.action_admin_panel);

        final String[] options = item.isAdmin()
                ? new String[]{switchLabel, addMemberLabel, adminPanelLabel, renameLabel, deleteLabel}
                : new String[]{switchLabel};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.getText())
                .setItems(options, (dialog, which) -> {
                    String chosen = options[which];

                    if (chosen.equals(switchLabel)) {
                        openServer(item);
                    } else if (chosen.equals(addMemberLabel)) {
                        showAddMemberDialog(item);
                    } else if (chosen.equals(adminPanelLabel)) {
                        showAdminPanelDialog(item);
                    } else if (chosen.equals(renameLabel)) {
                        showServerNameDialog(R.string.dialog_edit_server_title, R.string.hint_server_name,
                                R.string.action_save, item);
                    } else if (chosen.equals(deleteLabel)) {
                        showDeleteConfirmationDialog(item);
                    }
                })
                .show();
    }

    /**
     * Shows the admin panel dialog.
     *
     * @param item the item displayed by this row
     */
    private void showAdminPanelDialog(ServerViewItem item) {
        ServerConnectionManager.getInstance(requireContext()).fetchServerMembers(
                item.getServerId(), new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param members the channel or server members returned by the request
                     */
                    @Override
                    public void onResult(JSONArray members) {
                        if (!isAdded() || getContext() == null) return;
                        if (members == null || members.length() == 0) {
                            Toast.makeText(requireContext(), "No members found", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        final String[] memberDisplayNames = new String[members.length()];
                        final long[] memberUserIds = new long[members.length()];

                        for (int i = 0; i < members.length(); i++) {
                            JSONObject m = members.optJSONObject(i);
                            long userId = m != null ? m.optLong("userId", -1L) : -1L;
                            String role = m != null ? m.optString("role", "MEMBER") : "MEMBER";
                            memberUserIds[i] = userId;
                            memberDisplayNames[i] = "User ID: " + userId + " (" + role + ")";
                        }

                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.dialog_admin_panel_title, item.getText()))
                                .setItems(memberDisplayNames, (dialog, which) -> {
                                    long targetUserId = memberUserIds[which];
                                    showMemberActionDialog(item, targetUserId);
                                })
                                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                                .show();
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        if (isAdded()) {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * Shows the member action dialog.
     *
     * @param item the item displayed by this row
     * @param targetUserId the user ID that should receive the offer
     */
    private void showMemberActionDialog(ServerViewItem item, long targetUserId) {
        final String removeLabel = getString(R.string.action_remove_member);
        final String banLabel = getString(R.string.action_ban_user);
        final String unbanLabel = getString(R.string.action_unban_user);

        final String[] actions = new String[]{removeLabel, banLabel, unbanLabel};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Manage User " + targetUserId)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        // Remove from server
                        ServerConnectionManager.getInstance(requireContext()).removeServerMember(
                                item.getServerId(), targetUserId, new ServerConnectionManager.Callback<>() {
                                    /**
                                     * Handles the successful result of the asynchronous operation.
                                     *
                                     * @param result the result produced by the asynchronous operation
                                     */
                                    @Override
                                    public void onResult(Void result) {
                                        Toast.makeText(requireContext(), R.string.member_removed, Toast.LENGTH_SHORT).show();
                                    }

                                    /**
                                     * Handles a failure from the asynchronous operation.
                                     *
                                     * @param error the error message explaining the failure
                                     */
                                    @Override
                                    public void onFailure(String error) {
                                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                                    }
                                });
                    } else if (which == 1) {
                        // Ban user globally
                        ServerConnectionManager.getInstance(requireContext()).banUser(
                                targetUserId, new ServerConnectionManager.Callback<>() {
                                    /**
                                     * Handles the successful result of the asynchronous operation.
                                     *
                                     * @param result the result produced by the asynchronous operation
                                     */
                                    @Override
                                    public void onResult(Void result) {
                                        Toast.makeText(requireContext(), R.string.member_banned, Toast.LENGTH_SHORT).show();
                                    }

                                    /**
                                     * Handles a failure from the asynchronous operation.
                                     *
                                     * @param error the error message explaining the failure
                                     */
                                    @Override
                                    public void onFailure(String error) {
                                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                                    }
                                });
                    } else if (which == 2) {
                        // Unban user globally
                        ServerConnectionManager.getInstance(requireContext()).unbanUser(
                                targetUserId, new ServerConnectionManager.Callback<>() {
                                    /**
                                     * Handles the successful result of the asynchronous operation.
                                     *
                                     * @param result the result produced by the asynchronous operation
                                     */
                                    @Override
                                    public void onResult(Void result) {
                                        Toast.makeText(requireContext(), R.string.member_unbanned, Toast.LENGTH_SHORT).show();
                                    }

                                    /**
                                     * Handles a failure from the asynchronous operation.
                                     *
                                     * @param error the error message explaining the failure
                                     */
                                    @Override
                                    public void onFailure(String error) {
                                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                                    }
                                });
                    }
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Shows the add member dialog.
     *
     * @param item the item displayed by this row
     */
    private void showAddMemberDialog(ServerViewItem item) {
        final EditText input = new EditText(requireContext());
        input.setHint(R.string.hint_member_user_id);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_add_member_title)
                .setView(input)
                .setPositiveButton(R.string.action_add, (dialog, which) -> {
                    final String text = input.getText().toString().trim();
                    final long userId;
                    try {
                        userId = Long.parseLong(text);
                    } catch (NumberFormatException exception) {
                        Toast.makeText(requireContext(), R.string.error_invalid_member_user_id,
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    ServerConnectionManager.getInstance(requireContext()).addServerMember(
                            item.getServerId(), userId, new ServerConnectionManager.Callback<>() {
                                /**
                                 * Handles the successful result of the asynchronous operation.
                                 *
                                 * @param result the result produced by the asynchronous operation
                                 */
                                @Override
                                public void onResult(Void result) {
                                    Toast.makeText(requireContext(), R.string.member_added,
                                            Toast.LENGTH_SHORT).show();
                                }

                                /**
                                 * Handles a failure from the asynchronous operation.
                                 *
                                 * @param error the error message explaining the failure
                                 */
                                @Override
                                public void onFailure(String error) {
                                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Same navigation as a tap on the tile - "switch" to this virtual server.
     *
     * @param item the item displayed by this row
     */
    private void openServer(ServerViewItem item) {
        item.setUnreadCount(0);

        NavController navController = NavHostFragment.findNavController(this);
        Bundle args = new Bundle();
        args.putLong("serverId", item.getServerId());
        args.putString("serverName", item.getText());
        args.putBoolean("isAdminServer", item.isAdmin());
        navController.navigate(R.id.action_transform_to_rooms, args);
    }

    /**
     * Name dialog for both flows: {@code existing == null} creates a virtual server, otherwise the
     * name replaces that of {@code existing}.
     *
     * @param titleRes the string resource ID for the dialog title
     * @param hintRes the string resource ID for the input hint
     * @param positiveButtonRes the string resource ID for the positive button
     * @param existing the existing value or entry
     */
    private void showServerNameDialog(int titleRes, int hintRes, int positiveButtonRes,
                                      @androidx.annotation.Nullable ServerViewItem existing) {
        ServerNameDialogFragment dialog = ServerNameDialogFragment.newInstance(
                titleRes, hintRes, positiveButtonRes, existing == null ? "" : existing.getText());
        dialog.setOnNameConfirmed(name -> {
            if (existing == null) {
                transformViewModel.addServer(name);
            } else {
                transformViewModel.renameServer(existing.getServerId(), name);
            }
        });
        dialog.show(getParentFragmentManager(), "server_name_dialog");
    }

    /**
     * Shows the delete confirmation dialog.
     *
     * @param item the item displayed by this row
     */
    private void showDeleteConfirmationDialog(ServerViewItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_server_title)
                .setMessage(getString(R.string.dialog_delete_server_message, item.getText()))
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        transformViewModel.deleteServer(item.getServerId()))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Handles the resume event.
     *
     */
    @Override
    public void onResume() {
        super.onResume();
        if (transformViewModel != null) {
            transformViewModel.loadServers();
        }
    }

    /**
     * Handles the destroy view event.
     *
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Defines the contract for on server click listener.
     *
     */
    private interface OnServerClickListener {
        /**
         * Handles the server clicked event.
         *
         * @param server the server whose details are being updated
         */
        void onServerClicked(ServerViewItem server);

        /**
         * Handles the server long clicked event.
         *
         * @param item the item displayed by this row
         */
        void onServerLongClicked(ServerViewItem item);
    }

    /**
     * Represents transform adapter data or behavior used by the application.
     *
     */
    private static class TransformAdapter extends ListAdapter<ServerViewItem, TransformViewHolder> {

        @NonNull
        private final OnServerClickListener clickListener;

        protected TransformAdapter(@NonNull OnServerClickListener clickListener) {
            super(new DiffUtil.ItemCallback<ServerViewItem>() {
                /**
                 * Compares item identifiers to determine whether two list entries represent the same item.
                 *
                 * @param oldItem the earlier list item being compared
                 * @param newItem the updated list item being compared
                 * @return whether the operation succeeded
                 */
                @Override
                public boolean areItemsTheSame(@NonNull ServerViewItem oldItem, @NonNull ServerViewItem newItem) {
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
                public boolean areContentsTheSame(@NonNull ServerViewItem oldItem, @NonNull ServerViewItem newItem) {
                    return oldItem.getText().equals(newItem.getText())
                            && oldItem.getType().equals(newItem.getType())
                            && oldItem.isAdmin() == newItem.isAdmin()
                            && oldItem.getDrawableId() == newItem.getDrawableId()
                            && oldItem.getUnreadCount() == newItem.getUnreadCount();
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
        public TransformViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemTransformBinding binding = ItemTransformBinding.inflate(LayoutInflater.from(parent.getContext()));
            return new TransformViewHolder(binding);
        }

        /**
         * Handles the bind view holder event.
         *
         * @param holder the view holder to bind
         * @param position the item position in the adapter
         */
        @Override
        public void onBindViewHolder(@NonNull TransformViewHolder holder, int position) {
            ServerViewItem item = getItem(position);
            holder.textView.setText(item.getText());

            // The server type is what tells an organization apart from a direct message server.
            holder.subtitleView.setText(item.getType().isEmpty()
                    ? holder.subtitleView.getContext().getString(R.string.subtitle_server_item)
                    : item.getType());

            // Set bold text if server has unread messages
            if (item.hasUnread()) {
                holder.textView.setTypeface(null, Typeface.BOLD);
            } else {
                holder.textView.setTypeface(null, Typeface.NORMAL);
            }

            holder.imageView.setImageDrawable(
                    ResourcesCompat.getDrawable(holder.imageView.getResources(),
                            item.getDrawableId(),
                            null));

            // Click listener
            holder.itemView.setOnClickListener(v -> {
                item.setUnreadCount(0); // clear unread status on click
                clickListener.onServerClicked(item);
            });

            // Long click listener for switch/rename/delete options
            holder.itemView.setOnLongClickListener(v -> {
                clickListener.onServerLongClicked(item);
                return true;
            });
        }
    }

    /**
     * Represents transform view holder data or behavior used by the application.
     *
     */
    private static class TransformViewHolder extends RecyclerView.ViewHolder {

        private final ImageView imageView;
        private final TextView textView;
        private final TextView subtitleView;

        public TransformViewHolder(ItemTransformBinding binding) {
            super(binding.getRoot());
            imageView = binding.imageViewItemTransform;
            textView = binding.textViewItemTransform;
            subtitleView = binding.textServerSubtitle;
        }
    }
}
