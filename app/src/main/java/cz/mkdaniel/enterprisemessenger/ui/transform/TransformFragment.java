package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentTransformBinding;
import cz.mkdaniel.enterprisemessenger.databinding.ItemTransformBinding;

/**
 * Shows the list of servers. The number of columns auto-fits to the available width:
 * each cell is at least {@code @dimen/server_item_min_width} wide, so wider windows
 * (e.g. desktop freeform / tablet landscape) simply fit more columns per row instead of
 * stretching a fixed number of cells across the screen. The count is recomputed live on
 * resize and rotation.
 */
public class TransformFragment extends Fragment {

    private static final String TAG = "TransformGrid";

    private FragmentTransformBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        TransformViewModel transformViewModel =
                new ViewModelProvider(this).get(TransformViewModel.class);

        binding = FragmentTransformBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        NavController navController = NavHostFragment.findNavController(this);

        RecyclerView recyclerView = binding.recyclerviewTransform;

        // Compute the column count up front from the available width. The fragment is recreated on
        // rotation / freeform resize, so this runs again with the new size — no live layout
        // listener needed (and setSpanCount during a layout pass would not reflow bound items).
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int minItemWidthPx = (int) getResources().getDimension(R.dimen.server_item_min_width);
        final int horizontalMarginPx = (int) getResources().getDimension(R.dimen.fragment_horizontal_margin);
        int availableWidthPx = dm.widthPixels - 2 * horizontalMarginPx;
        int columns = Math.max(1, availableWidthPx / minItemWidthPx);

        Log.d(TAG, "onCreateView: widthPixels=" + dm.widthPixels
                + ", availableWidthPx=" + availableWidthPx
                + ", minItemWidthPx=" + minItemWidthPx
                + ", computedColumns=" + columns);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(requireContext(), columns);
        recyclerView.setLayoutManager(gridLayoutManager);

        ListAdapter<ServerViewItem, TransformViewHolder> adapter =
                new TransformAdapter((serverIndex, serverName) -> {
                    // Navigate to the rooms screen for this server
                    Bundle args = new Bundle();
                    args.putInt("serverIndex", serverIndex);
                    args.putString("serverName", serverName);
                    navController.navigate(R.id.nav_rooms, args);
                });
        recyclerView.setAdapter(adapter);
        transformViewModel.getItems().observe(getViewLifecycleOwner(), adapter::submitList);
        return root;
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
                    return oldItem.getText().equals(newItem.getText());
                }

                @Override
                public boolean areContentsTheSame(@NonNull ServerViewItem oldItem, @NonNull ServerViewItem newItem) {
                    return oldItem.getText().equals(newItem.getText())
                            && oldItem.getDrawableId() == newItem.getDrawableId();
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
            holder.imageView.setImageDrawable(
                    ResourcesCompat.getDrawable(holder.imageView.getResources(),
                            item.getDrawableId(),
                            null));
            // Clicking any server row navigates to its rooms
            holder.itemView.setOnClickListener(v -> clickListener.onServerClicked(position, item.getText()));
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
        void onServerClicked(int serverIndex, String serverName);
    }
}
