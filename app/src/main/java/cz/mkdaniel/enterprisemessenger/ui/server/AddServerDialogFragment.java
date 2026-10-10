package cz.mkdaniel.enterprisemessenger.ui.server;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.ui.transform.ServerViewItem;
import cz.mkdaniel.enterprisemessenger.ui.transform.TransformViewModel;

/**
 * Modal Dialog Fragment for adding a new Server by IP address/hostname.
 */
public class AddServerDialogFragment extends DialogFragment {

    public static AddServerDialogFragment newInstance() {
        return new AddServerDialogFragment();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_add_server, null);

        EditText editName = view.findViewById(R.id.edittext_server_name);
        EditText editIp = view.findViewById(R.id.edittext_server_ip);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setPositiveButton(R.string.action_add, null) // Listener attached onStart to prevent auto-close on error
                .setNegativeButton(R.string.action_cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String ipAddress = editIp.getText() != null ? editIp.getText().toString().trim() : "";
                String serverName = editName.getText() != null ? editName.getText().toString().trim() : "";

                if (TextUtils.isEmpty(ipAddress)) {
                    editIp.setError("Please enter a valid IP address or hostname");
                    return;
                }

                TransformViewModel viewModel = new ViewModelProvider(requireActivity()).get(TransformViewModel.class);
                ServerViewItem newServer = viewModel.addServer(serverName, ipAddress);

                Toast.makeText(requireContext(),
                        "Added server: " + newServer.getText() + " (" + newServer.getIpAddress() + ")",
                        Toast.LENGTH_SHORT).show();

                dismiss();
            });
        });

        return dialog;
    }
}
