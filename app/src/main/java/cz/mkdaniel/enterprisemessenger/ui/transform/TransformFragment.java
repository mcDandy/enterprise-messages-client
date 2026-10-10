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
import androidx.appcompat.app.AlertDialog;
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
import cz.mkdaniel.enterprisemessenger.ui.server.EditServerDialogFragment;

/**
 * Shows the list of servers. The number of columns auto-fits to the available width:
 * each cell is at least {@code @dimen/server_item_min_width} wide.
 * Supports long press on server items to edit or delete them.
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

        NavController navController = NavHostFragment.findNavController(this);

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
                    public void onServerClicked(int serverIndex, String serverName, String serverIp) {
                        // Cancel notification for this server when user opens it
                        NotificationHelper.cancelNotificationForServer(requireContext(), serverName);

                        // Navigate to the rooms screen for this server
                        Bundle args = new Bundle();
                        args.putInt("serverIndex", serverIndex);
                        args.putString("serverName", serverName);
                        args.putString("serverIp", serverIp);
                        navController.navigate(R.id.nav_rooms, args);
                    }

                    @Override
                    public void onServerLongClicked(ServerViewItem item) {
                        showServerOptionsDialog(item);
                    }
                });
        recyclerView.setAdapter(adapter);

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

        return root;
    }

    private void showServerOptionsDialog(ServerViewItem item) {
        String[] options = new String[]{
                getString(R.string.action_edit),
                getString(R.string.action_delete)
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.getText())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) { // Edit
                        EditServerDialogFragment editDialog = EditServerDialogFragment.newInstance(
                                item.getId(),
                                item.getText(),
                                item.getIpAddress()
                        );
                        editDialog.show(getParentFragmentManager(), "edit_server_dialog");
                    } else if (which == 1) { // Delete
                        showDeleteConfirmationDialog(item);
                    }
                })
                .show();
    }

    private void showDeleteConfirmationDialog(ServerViewItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_server_title)
                .setMessage(getString(R.string.dialog_delete_server_message, item.getText()))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    boolean deleted = transformViewModel.deleteServer(item.getId());
                    if (deleted) {
                        Toast.makeText(requireContext(), "Server deleted", Toast.LENGTH_SHORT).show();
                    }
                })
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
                            && oldItem.getIpAddress().equals(newItem.getIpAddress())
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
                clickListener.onServerClicked(position, item.getText(), item.getIpAddress());
            });

            // Long click listener for edit/delete options
            holder.itemView.setOnLongClickListener(v -> {
                clickListener.onServerLongClicked(item);
                return true;
            });
        }
    }

    private static class TransformViewHolder extends RecyclerView.ViewHolder {

        private final ImageView imageView;
        private final TextView textView;

        public TransformViewHolder(ItemTransformBinding binding) {
            super(binding.getRoot());
            imageView = binding.imageViewItemTransform;
            textView = binding.textViewItemTransform;
        }
    }

    private interface OnServerClickListener {
        void onServerClicked(int serverIndex, String serverName, String serverIp);
        void onServerLongClicked(ServerViewItem item);
    }
}
