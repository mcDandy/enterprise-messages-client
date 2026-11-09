package cz.mkdaniel.enterprisemessenger.ui.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.crypto.AppSecrets;
import cz.mkdaniel.enterprisemessenger.crypto.KeyBackup;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentSettingsBinding;
import cz.mkdaniel.enterprisemessenger.net.AccountStore;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

/**
 * Account controls and the user-facing backup of the chat keys.
 * <p>
 * The backup exists because the channel keys are sealed by an Android KeyStore key that cannot be
 * exported and does not survive a restore onto another device: lose the phone, and the message
 * history is unreadable. The user therefore gets an explicit, passphrase-protected archive he keeps
 * himself. The app writes it only to a document he picks through the system picker and never uploads
 * it anywhere.
 */
public class SettingsFragment extends Fragment {

    /** Suggested name of the archive; the user can rename it in the system picker. */
    private static final String BACKUP_FILE_NAME = "enterprise-messenger-keys.txt";

    /** Mime type the picker is offered for the archive: it is armored text, by design. */
    private static final String BACKUP_MIME_TYPE = "text/plain";

    /** PBKDF2 cost makes derivation slow, so the work must never touch the main thread. */
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();

    private FragmentSettingsBinding binding;

    private final ActivityResultLauncher<String> createDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
                    uri -> {
                        if (uri == null) {
                            return;
                        }

                        askForPassphrase(R.string.dialog_backup_passphrase_title, true,
                                passphrase -> exportKeys(uri, passphrase));
                    });

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }

                askForPassphrase(R.string.dialog_restore_passphrase_title, false,
                        passphrase -> importKeys(uri, passphrase));
            });

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        SettingsViewModel settingsViewModel =
                new ViewModelProvider(this).get(SettingsViewModel.class);

        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        settingsViewModel.getText().observe(getViewLifecycleOwner(), binding.textSettings::setText);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        AccountStore accountStore = new AccountStore(requireContext());
        if (accountStore.isLoggedIn()) {
            binding.textLoggedUser.setText(getString(R.string.text_logged_in_as, accountStore.getUsername()));
        } else {
            binding.textLoggedUser.setText(R.string.text_not_logged_in);
        }

        binding.buttonLogout.setOnClickListener(v -> {
            new AccountStore(requireContext()).clearSession();
            ServerConnectionManager.getInstance(requireContext()).disconnect();
            NavController navController = NavHostFragment.findNavController(this);
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.nav_transform, true)
                    .build();
            navController.navigate(R.id.nav_login, null, navOptions);
        });

        binding.buttonBackupKeys.setOnClickListener(v ->
                createDocumentLauncher.launch(BACKUP_FILE_NAME));

        binding.buttonRestoreKeys.setOnClickListener(v ->
                openDocumentLauncher.launch(new String[]{BACKUP_MIME_TYPE, "application/octet-stream"}));
    }

    /**
     * Writes the archive to the document the user chose. The armored text is produced off the main
     * thread because PBKDF2 with 210 000 iterations takes a visible fraction of a second.
     */
    private void exportKeys(final Uri target, final char[] passphrase) {
        final Context context = requireContext().getApplicationContext();

        backupExecutor.execute(() -> postToast(exportResult(context, target, passphrase)));
    }

    /** Body of {@link #exportKeys}; returns the message to show the user. */
    private String exportResult(Context context, Uri target, char[] passphrase) {
        try {
            final AppSecrets secrets = AppSecrets.getInstance(context);
            final int channels = secrets.channelKeys().exportableEntries().size();
            final String armored = secrets.keyBackup().export(passphrase);

            writeText(context, target, armored);

            return getString(R.string.backup_exported, channels);
        } catch (IllegalStateException nothingToBackUp) {
            return getString(R.string.backup_error_empty);
        } catch (Exception failure) {
            return failure.getMessage() == null
                    ? getString(R.string.backup_error_not_archive)
                    : failure.getMessage();
        }
    }

    /**
     * Reads and adopts the archive the user chose. A wrong passphrase or a foreign file changes
     * nothing: {@link KeyBackup#importArchive} decrypts and parses everything before adopting.
     */
    private void importKeys(final Uri source, final char[] passphrase) {
        final Context context = requireContext().getApplicationContext();

        backupExecutor.execute(() -> postToast(importResult(context, source, passphrase)));
    }

    /** Body of {@link #importKeys}; returns the message to show the user. */
    private String importResult(Context context, Uri source, char[] passphrase) {
        try {
            final String armored = readText(context, source);
            final int channels = AppSecrets.getInstance(context).keyBackup()
                    .importArchive(armored, passphrase);

            return getString(R.string.backup_restored, channels);
        } catch (KeyBackup.BackupFormatException formatFailure) {
            return getString(formatFailure.isWrongPassphrase()
                    ? R.string.backup_error_wrong_passphrase
                    : R.string.backup_error_not_archive);
        } catch (Exception failure) {
            return getString(R.string.backup_error_not_archive);
        }
    }

    /**
     * Asks for the archive passphrase. On export a confirmation field appears, because an archive
     * whose passphrase is forgotten is worthless and the user only finds out after losing the phone.
     */
    private void askForPassphrase(final int titleRes, final boolean confirmToo,
                                  final java.util.function.Consumer<char[]> onAccepted) {
        final LinearLayout fields = new LinearLayout(requireContext());
        fields.setOrientation(LinearLayout.VERTICAL);
        final int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding, padding, 0);

        final EditText passphraseField = newPassphraseField(R.string.dialog_passphrase_hint);
        fields.addView(passphraseField);

        final EditText confirmField = confirmToo ? newPassphraseField(R.string.dialog_passphrase_confirm_hint) : null;
        if (confirmField != null) {
            fields.addView(confirmField);
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setMessage(confirmToo ? R.string.backup_warning : 0)
                .setView(fields)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final char[] passphrase = charsOf(passphraseField);

                    if (passphrase.length < 8) {
                        toast(R.string.backup_error_short_passphrase);

                        return;
                    }

                    if (confirmField != null
                            && !java.util.Arrays.equals(passphrase, charsOf(confirmField))) {
                        toast(R.string.backup_error_mismatch);

                        return;
                    }

                    onAccepted.accept(passphrase);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private EditText newPassphraseField(int hintRes) {
        final EditText field = new EditText(requireContext());

        field.setHint(hintRes);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        return field;
    }

    private static char[] charsOf(EditText field) {
        final char[] value = new char[field.getText().length()];
        field.getText().getChars(0, value.length, value, 0);

        return value;
    }

    private static void writeText(Context context, Uri target, String text) throws Exception {
        try (final OutputStream out = context.getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) {
                throw new IllegalStateException("The document could not be opened for writing");
            }

            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(Context context, Uri source) throws Exception {
        final StringBuilder builder = new StringBuilder();

        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getContentResolver().openInputStream(source),
                        StandardCharsets.UTF_8))) {
            final char[] chunk = new char[4096];
            int read;

            while ((read = reader.read(chunk)) != -1) {
                builder.append(chunk, 0, read);
            }
        }

        return builder.toString();
    }

    private void postToast(final String message) {
        if (!isAdded() || getActivity() == null) {
            return;
        }

        getActivity().runOnUiThread(() -> toastText(message));
    }

    private void toast(int messageRes) {
        toastText(getString(messageRes));
    }

    private void toastText(String message) {
        if (!isAdded()) {
            return;
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        backupExecutor.shutdown();
    }
}
