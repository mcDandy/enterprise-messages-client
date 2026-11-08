package cz.mkdaniel.enterprisemessenger.ui.server;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import cz.mkdaniel.enterprisemessenger.R;

/**
 * Name-only dialog shared by the virtual servers and channels screens for creating and renaming
 * either of them. It deliberately asks for a name alone: virtual servers and channels live on the
 * one physical server, whose address is compile-time configuration and therefore never an input.
 * <p>
 * The result goes back through {@link OnNameConfirmed} on the fragment that showed the dialog.
 */
public class ServerNameDialogFragment extends DialogFragment {

    private static final String ARG_TITLE_RES = "title_res";
    private static final String ARG_HINT_RES = "hint_res";
    private static final String ARG_CURRENT_NAME = "current_name";
    private static final String ARG_POSITIVE_BUTTON_RES = "positive_button_res";

    /**
     * Called with the confirmed, already trimmed name.
     */
    public interface OnNameConfirmed {
        void onNameConfirmed(String name);
    }

    @Nullable
    private OnNameConfirmed listener;

    public static ServerNameDialogFragment newInstance(
            @StringRes int titleRes,
            @StringRes int hintRes,
            @StringRes int positiveButtonRes,
            @Nullable String currentName) {

        ServerNameDialogFragment fragment = new ServerNameDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_TITLE_RES, titleRes);
        args.putInt(ARG_HINT_RES, hintRes);
        args.putInt(ARG_POSITIVE_BUTTON_RES, positiveButtonRes);
        args.putString(ARG_CURRENT_NAME, currentName == null ? "" : currentName);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnNameConfirmed(@Nullable OnNameConfirmed listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        @StringRes int titleRes = args.getInt(ARG_TITLE_RES, R.string.dialog_create_server_title);
        @StringRes int hintRes = args.getInt(ARG_HINT_RES, R.string.hint_server_name);
        @StringRes int positiveButtonRes = args.getInt(ARG_POSITIVE_BUTTON_RES, R.string.action_save);
        String currentName = args.getString(ARG_CURRENT_NAME, "");

        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_server_name, null);

        TextView textTitle = view.findViewById(R.id.textview_dialog_title);
        TextInputLayout layoutName = view.findViewById(R.id.layout_name);
        TextInputEditText editName = view.findViewById(R.id.edittext_name);

        textTitle.setText(titleRes);
        layoutName.setHint(hintRes);
        editName.setText(currentName);
        editName.setSelection(currentName.length());

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setPositiveButton(positiveButtonRes, null)
                .setNegativeButton(R.string.action_cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            editName.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = editName.getText() != null ? editName.getText().toString().trim() : "";

                // The server rejects an empty name and a name longer than 100 characters, so the
                // dialog blocks it instead of letting the request fail with a validation error.
                if (TextUtils.isEmpty(name)) {
                    editName.setError(getString(R.string.error_empty_fields));
                    return;
                }

                if (listener != null) {
                    listener.onNameConfirmed(name);
                }
                dismiss();
            });
        });

        return dialog;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}
