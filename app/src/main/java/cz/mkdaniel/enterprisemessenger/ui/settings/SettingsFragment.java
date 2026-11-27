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
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.crypto.AppSecrets;
import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
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

    /**
     * Suggested name of the archive; the user can rename it in the system picker.
     */
    private static final String BACKUP_FILE_NAME = "enterprise-messenger-keys.txt";

    /**
     * Mime type the picker is offered for the archive: it is armored text, by design.
     */
    private static final String BACKUP_MIME_TYPE = "text/plain";

    /**
     * PBKDF2 cost makes derivation slow, so the work must never touch the main thread.
     */
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();
    /**
     * Both launchers are registered in {@link #onViewCreated} rather than as field initializers.
     * Registering during construction leaks {@code this} into the fragment's activity-result state
     * before the fragment is built, where a restart replaying a pending result would reach a
     * half-constructed fragment and run its callbacks against fields that are still null.
     */
    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String[]> openDocumentLauncher;
    private FragmentSettingsBinding binding;

    /**
     * Copies the current text field contents into a character array.
     *
     * @param field the text field whose contents are read
     * @return the chars of data
     */
    private static char[] charsOf(EditText field) {
        final char[] value = new char[field.getText().length()];
        field.getText().getChars(0, value.length, value, 0);

        return value;
    }

    /**
     * Writes the text.
     *
     * @param context the Android context used to access application resources and services
     * @param target the destination byte array
     * @param text the text to process or send
     * @throws Exception if the write text operation cannot be completed.
     * @throws IllegalStateException if the current state does not allow this operation.
     */
    private static void writeText(Context context, Uri target, String text) throws Exception {
        try (final OutputStream out = context.getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) {
                throw new IllegalStateException("The document could not be opened for writing");
            }

            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Reads the text.
     *
     * @param context the Android context used to access application resources and services
     * @param source the source byte array
     * @return the read text result
     * @throws Exception if the read text operation cannot be completed.
     */
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
        SettingsViewModel settingsViewModel =
                new ViewModelProvider(this).get(SettingsViewModel.class);

        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        settingsViewModel.getText().observe(getViewLifecycleOwner(), binding.textSettings::setText);
        return root;
    }

    /**
     * Handles the view created event.
     *
     * @param view the view to create, inspect, or update
     * @param savedInstanceState the saved instance state to restore, or null when creating the screen for the first time
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE), uri -> {
                    if (uri == null) {
                        return;
                    }

                    askForPassphrase(R.string.dialog_backup_passphrase_title, true,
                            passphrase -> exportKeys(uri, passphrase));
                });
        openDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }

                    askForPassphrase(R.string.dialog_restore_passphrase_title, false,
                            passphrase -> importKeys(uri, passphrase));
                });

        AccountStore accountStore = new AccountStore(requireContext());
        if (accountStore.isLoggedIn()) {
            binding.textLoggedUser.setText(getString(R.string.text_logged_in_as, accountStore.getUsername()));

            // Filled from the cache first so the card shows something at once, then refreshed from the
            // server. A failed read leaves the cached values up rather than blanking them.
            renderProfile(accountStore.getDisplayName(), accountStore.getUsername(),
                    accountStore.getUserId(), accountStore.getRegisteredAt());
            refreshProfile();
        } else {
            binding.textLoggedUser.setText(R.string.text_not_logged_in);
            hideProfile();
        }

        binding.buttonLogout.setOnClickListener(v -> {
            new AccountStore(requireContext()).clearSession();
            // Closing the socket is not logging out: the manager still holds the token and the
            // channel key in memory, and the next connect() would resume the session that was just
            // ended.
            ServerConnectionManager.getInstance(requireContext()).clearSessionState();
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

        binding.buttonEditDisplayName.setOnClickListener(v -> showDisplayNameDialog());
        binding.buttonResetIdentity.setOnClickListener(v -> confirmIdentityReset());
    }

    /**
     * Asks the server for the signed-in account's profile and repaints the card with it. Failure is
     * silent by design: the cached values are already on screen, and a toast here would blame the user
     * for a network they cannot fix from this screen.
     */
    private void refreshProfile() {
        ServerConnectionManager.getInstance(requireContext()).fetchProfile(
                new ServerConnectionManager.Callback<>() {
                    /**
                     * Handles the successful result of the asynchronous operation.
                     *
                     * @param profile the profile returned by the server
                     */
                    @Override
                    public void onResult(ServerConnectionManager.Profile profile) {
                        if (!isAdded() || binding == null) {
                            return;
                        }

                        renderProfile(profile.displayName, profile.username,
                                profile.userId, profile.registeredAt);
                    }

                    /**
                     * Handles a failure from the asynchronous operation.
                     *
                     * @param error the error message explaining the failure
                     */
                    @Override
                    public void onFailure(String error) {
                        // The cached values stay up; there is nothing the user can do from here.
                    }
                });
    }

    /**
     * Paints the About-you block. A row with no value is hidden rather than showing a placeholder, so
     * an account that has never set a display name simply shows its handle as the heading.
     *
     * @param registeredAt the server's ISO-8601 instant, or "" when unknown
     *
     * @param displayName the display name to set
     * @param username the account username
     * @param userId the identifier of the user
     */
    private void renderProfile(String displayName, String username, long userId, String registeredAt) {
        if (binding == null) {
            return;
        }

        // The handle is the fallback heading, so it earns its own line only when it differs from the
        // display name; otherwise the card would print the same word twice.
        final boolean showUsernameLine = !username.isEmpty() && !username.equals(displayName);

        binding.textDisplayName.setText(displayName.isEmpty() ? username : displayName);
        binding.textDisplayName.setVisibility(username.isEmpty() && displayName.isEmpty()
                ? View.GONE
                : View.VISIBLE);

        binding.textUsername.setText(getString(R.string.label_username, username));
        binding.textUsername.setVisibility(showUsernameLine ? View.VISIBLE : View.GONE);

        if (userId > 0) {
            binding.textUserId.setText(getString(R.string.label_user_id, userId));
            binding.textUserId.setVisibility(View.VISIBLE);
        } else {
            binding.textUserId.setVisibility(View.GONE);
        }

        final String registeredOn = formatRegistrationDate(registeredAt);
        if (registeredOn == null) {
            binding.textRegisteredAt.setVisibility(View.GONE);
        } else {
            binding.textRegisteredAt.setText(getString(R.string.label_registered_at, registeredOn));
            binding.textRegisteredAt.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Hides the profile.
     *
     */
    private void hideProfile() {
        if (binding == null) {
            return;
        }

        binding.textDisplayName.setVisibility(View.GONE);
        binding.textUsername.setVisibility(View.GONE);
        binding.textUserId.setVisibility(View.GONE);
        binding.textRegisteredAt.setVisibility(View.GONE);
    }

    /**
     * Turns the server's ISO-8601 instant into a date in the phone's zone and locale. Returns null for
     * a blank or unparseable value, which hides the row: an approximate or invented date would be worse
     * than none on a screen that otherwise reports exactly what the server says.
     *
     * @param registeredAt the account registration timestamp
     * @return the format registration date result
     */
    private String formatRegistrationDate(String registeredAt) {
        if (registeredAt == null || registeredAt.isEmpty()) {
            return null;
        }

        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(getResources().getConfiguration().getLocales().get(0))
                    .format(java.time.OffsetDateTime.parse(registeredAt)
                            .atZoneSameInstant(java.time.ZoneId.systemDefault()));
        } catch (DateTimeException unparsable) {
            // Covers DateTimeParseException, which extends it.
            return null;
        }
    }

    /**
     * Writes the archive to the document the user chose. The armored text is produced off the main
     * thread because PBKDF2 with 210 000 iterations takes a visible fraction of a second.
     *
     * @param target the destination byte array
     * @param passphrase the passphrase protecting or unlocking the backup
     */
    private void exportKeys(final Uri target, final char[] passphrase) {
        final Context context = requireContext().getApplicationContext();

        backupExecutor.execute(() -> postToast(exportResult(context, target, passphrase)));
    }

    /**
     * Body of {@link #exportKeys}; returns the message to show the user.
     *
     * @param context the Android context used to access application resources and services
     * @param target the destination byte array
     * @param passphrase the passphrase protecting or unlocking the backup
     * @return the export result result
     */
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
     *
     * @param source the source byte array
     * @param passphrase the passphrase protecting or unlocking the backup
     */
    private void importKeys(final Uri source, final char[] passphrase) {
        final Context context = requireContext().getApplicationContext();

        backupExecutor.execute(() -> postToast(importResult(context, source, passphrase)));
    }

    /**
     * Body of {@link #importKeys}; returns the message to show the user.
     *
     * @param context the Android context used to access application resources and services
     * @param source the source byte array
     * @param passphrase the passphrase protecting or unlocking the backup
     * @return the import result result
     */
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
     * Asks for the name other members see. Prefills with the current display name rather than the login
     * handle: they are different identities, and editing one must not offer the other as its starting
     * value. An account that has never set a display name falls back to the handle as the suggestion.
     */
    private void showDisplayNameDialog() {
        final AccountStore accountStore = new AccountStore(requireContext());
        final EditText field = new EditText(requireContext());
        field.setHint(R.string.hint_display_name);

        final String currentDisplayName = accountStore.getDisplayName();
        field.setText(currentDisplayName.isEmpty()
                ? accountStore.getUsername()
                : currentDisplayName);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_edit_display_name_title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String displayName = field.getText().toString().trim();
                    ServerConnectionManager.getInstance(requireContext()).updateDisplayName(
                            displayName,
                            new ServerConnectionManager.Callback<>() {
                                /**
                                 * Handles the successful result of the asynchronous operation.
                                 *
                                 * @param result the result produced by the asynchronous operation
                                 */
                                @Override
                                public void onResult(Void result) {
                                    // text_logged_user keeps reporting the login handle; only the
                                    // profile rows describe the display name. Re-read rather than patch
                                    // locally so the card cannot drift from what the server stored.
                                    refreshProfile();
                                    toastText("Display name updated");
                                }

                                /**
                                 * Handles a failure from the asynchronous operation.
                                 *
                                 * @param error the error message explaining the failure
                                 */
                                @Override
                                public void onFailure(String error) {
                                    toastText(error);
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Asks the user to confirm resetting the local identity.
     *
     */
    private void confirmIdentityReset() {
        final String bound = new AccountStore(requireContext()).boundAccount();

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_reset_identity_title)
                .setMessage(bound.isEmpty()
                        ? getString(R.string.dialog_reset_identity_body_unbound)
                        : getString(R.string.dialog_reset_identity_body, bound))
                .setPositiveButton(R.string.action_reset_identity, (dialog, which) -> resetIdentity())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Hands the device over: the identity key pair leaves the KeyStore, every sealed channel and
     * peer key is dropped, and the binding is released so the next account can claim the device.
     * Deleting a KeyStore entry is slow enough to stay off the main thread, like the backup work.
     */
    private void resetIdentity() {
        final Context context = requireContext().getApplicationContext();

        backupExecutor.execute(() -> {
            postToast(resetResult(context));

            if (!isAdded() || getActivity() == null) {
                return;
            }

            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }

                // The identity behind this session has just been deleted, so the token and the
                // channel key the manager still holds have to go with it - not only the socket.
                ServerConnectionManager.getInstance(context).clearSessionState();

                NavHostFragment.findNavController(this).navigate(R.id.nav_login, null,
                        new NavOptions.Builder().setPopUpTo(R.id.nav_transform, true).build());
            });
        });
    }

    /**
     * The irreversible part of {@link #resetIdentity}; returns the message to show the user.
     * <p>
     * The session is cleared before the key is deleted, and the binding only after the KeyStore
     * confirms the entry is gone: a device whose identity is still there must stay bound to its
     * account, otherwise the next sign-in would inherit keys it cannot prove it owns.
     *
     * @param context the Android context used to access application resources and services
     * @return the reset result result
     */
    private String resetResult(Context context) {
        final AppSecrets secrets = AppSecrets.getInstance(context);

        secrets.channelKeys().forgetAll();
        secrets.peerSecrets().forgetAll();
        // Both registries write through this store, so this also drops the sealed token and any slot
        // a future registry adds: nothing of the old account is left to read.
        secrets.blobs().clearAll();

        final AccountStore accountStore = new AccountStore(context);
        accountStore.clearSession();

        if (!CryptoManager.getInstance(context).deleteIdentityKey()) {
            return getString(R.string.identity_reset_failed);
        }

        accountStore.unbindAccount();

        return getString(R.string.identity_reset_done);
    }

    /**
     * Asks for the archive passphrase. On export a confirmation field appears, because an archive
     * whose passphrase is forgotten is worthless and the user only finds out after losing the phone.
     *
     * @param titleRes the string resource ID for the dialog title
     * @param confirmToo whether to also request confirmation
     * @param onAccepted the action to run after the identity is accepted
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

    /**
     * Creates a password field for entering a backup passphrase.
     *
     * @param hintRes the string resource ID for the input hint
     * @return the new passphrase field result
     */
    private EditText newPassphraseField(int hintRes) {
        final EditText field = new EditText(requireContext());

        field.setHint(hintRes);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        return field;
    }

    /**
     * Posts a message to be shown as a toast on the UI thread.
     *
     * @param message the message to process
     */
    private void postToast(final String message) {
        if (!isAdded() || getActivity() == null) {
            return;
        }

        getActivity().runOnUiThread(() -> toastText(message));
    }

    /**
     * Converts the value to ast.
     *
     * @param messageRes the string resource ID for the toast message
     */
    private void toast(int messageRes) {
        toastText(getString(messageRes));
    }

    /**
     * Displays the supplied text as a toast.
     *
     * @param message the message to process
     */
    private void toastText(String message) {
        if (!isAdded()) {
            return;
        }

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
    }

    /**
     * Handles the destroy event.
     *
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        backupExecutor.shutdown();
    }
}
