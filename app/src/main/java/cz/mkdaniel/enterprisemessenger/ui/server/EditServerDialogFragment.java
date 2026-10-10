package cz.mkdaniel.enterprisemessenger.ui.server;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.ui.transform.TransformViewModel;

/**
 * Modal Dialog Fragment for editing an existing Server entry.
 */
public class EditServerDialogFragment extends DialogFragment {

    private static final String ARG_SERVER_ID = "server_id";
    private static final String ARG_SERVER_NAME = "server_name";
    private static final String ARG_SERVER_IP = "server_ip";

    public static EditServerDialogFragment newInstance(String serverId, String serverName, String serverIp) {
        EditServerDialogFragment fragment = new EditServerDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SERVER_ID, serverId);
        args.putString(ARG_SERVER_NAME, serverName);
        args.putString(ARG_SERVER_IP, serverIp);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String serverId = getArguments() != null ? getArguments().getString(ARG_SERVER_ID, "") : "";
        String serverName = getArguments() != null ? getArguments().getString(ARG_SERVER_NAME, "") : "";
        String serverIp = getArguments() != null ? getArguments().getString(ARG_SERVER_IP, "") : "";

        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_add_server, null);

        TextView textTitle = view.findViewById(R.id.textview_dialog_title);
        EditText editName = view.findViewById(R.id.edittext_server_name);
        EditText editIp = view.findViewById(R.id.edittext_server_ip);

        textTitle.setText(R.string.dialog_edit_server_title);
        editName.setText(serverName);
        editIp.setText(serverIp);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String ipAddress = editIp.getText() != null ? editIp.getText().toString().trim() : "";
                String name = editName.getText() != null ? editName.getText().toString().trim() : "";

                if (TextUtils.isEmpty(ipAddress)) {
                    editIp.setError("Please enter a valid IP address or hostname");
                    return;
                }

                TransformViewModel viewModel = new ViewModelProvider(requireActivity()).get(TransformViewModel.class);
                viewModel.updateServer(serverId, name, ipAddress);

                Toast.makeText(requireContext(), "Server updated", Toast.LENGTH_SHORT).show();
                dismiss();
            });
        });

        return dialog;
    }
}
