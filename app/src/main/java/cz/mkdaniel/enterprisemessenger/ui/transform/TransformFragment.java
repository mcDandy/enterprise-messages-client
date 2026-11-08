package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import cz.mkdaniel.enterprisemessenger.notification.NotificationHelper;
import cz.mkdaniel.enterprisemessenger.ui.server.ServerNameDialogFragment;

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
                    @Override
                    public void onServerClicked(ServerViewItem server) {
                        // Cancel notification for this server when user opens it
                        NotificationHelper.cancelNotificationForServer(requireContext(), server.getText());
                        openServer(server);
                    }

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
     */
    private void showServerOptionsDialog(ServerViewItem item) {
        final String switchLabel = getString(R.string.action_switch_server);
        final String renameLabel = getString(R.string.action_rename);
        final String deleteLabel = getString(R.string.action_delete);

        final String[] options = item.isAdmin()
                ? new String[]{switchLabel, renameLabel, deleteLabel}
                : new String[]{switchLabel};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.getText())
                .setItems(options, (dialog, which) -> {
                    String chosen = options[which];

                    if (chosen.equals(switchLabel)) {
                        openServer(item);
                    } else if (chosen.equals(renameLabel)) {
                        showServerNameDialog(R.string.dialog_edit_server_title, R.string.hint_server_name,
                                R.string.action_save, item);
                    } else if (chosen.equals(deleteLabel)) {
                        showDeleteConfirmationDialog(item);
                    }
                })
                .show();
    }

    /** Same navigation as a tap on the tile - "switch" to this virtual server. */
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

    private void showDeleteConfirmationDialog(ServerViewItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_server_title)
                .setMessage(getString(R.string.dialog_delete_server_message, item.getText()))
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        transformViewModel.deleteServer(item.getServerId()))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (transformViewModel != null) {
            transformViewModel.loadServers();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static class TransformAdapter extends ListAdapter<ServerViewItem, TransformViewHolder> {

        @NonNull
        private final OnServerClickListener clickListener;

        protected TransformAdapter(@NonNull OnServerClickListener clickListener) {
            super(new DiffUtil.ItemCallback<ServerViewItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull ServerViewItem oldItem, @NonNull ServerViewItem newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

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

        @NonNull
        @Override
        public TransformViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemTransformBinding binding = ItemTransformBinding.inflate(LayoutInflater.from(parent.getContext()));
            return new TransformViewHolder(binding);
        }

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

    private interface OnServerClickListener {
        void onServerClicked(ServerViewItem server);
        void onServerLongClicked(ServerViewItem item);
    }
}
