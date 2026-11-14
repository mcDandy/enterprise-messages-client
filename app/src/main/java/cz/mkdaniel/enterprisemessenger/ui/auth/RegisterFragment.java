package cz.mkdaniel.enterprisemessenger.ui.auth;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentRegisterBinding;
import cz.mkdaniel.enterprisemessenger.net.AccountStore;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

public class RegisterFragment extends Fragment {

    private FragmentRegisterBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = NavHostFragment.findNavController(this);

        binding.buttonRegister.setOnClickListener(v -> performRegister(navController));

        binding.buttonGoToLogin.setOnClickListener(v -> navController.navigate(R.id.action_register_to_login));
    }

    private void performRegister(NavController navController) {
        String username = binding.edittextUsername.getText() != null ? binding.edittextUsername.getText().toString().trim() : "";
        String password = binding.edittextPassword.getText() != null ? binding.edittextPassword.getText().toString().trim() : "";
        String confirmPassword = binding.edittextConfirmPassword.getText() != null ? binding.edittextConfirmPassword.getText().toString().trim() : "";

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password) || TextUtils.isEmpty(confirmPassword)) {
            showError(getString(R.string.error_empty_fields));
            return;
        }

        if (!password.equals(confirmPassword)) {
            showError(getString(R.string.error_password_mismatch));
            return;
        }

        // A second account registered on this device would be handed this device's public key while
        // the first account's channel keys stayed sealed on it. Channel ids are global, so the two
        // accounts would then be able to open each other's channels - registration stops here.
        final AccountStore accountStore = new AccountStore(requireContext());
        if (!accountStore.isBoundTo(username)) {
            showError(getString(R.string.error_device_bound_for_register, accountStore.boundAccount()));
            return;
        }

        hideError();
        setLoading(true);

        ServerConnectionManager connectionManager = ServerConnectionManager.getInstance(requireContext());
        // No address is passed around: registration happens on the deployment address from
        // @string/server_ip, which is also recorded as the issuer of the returned token.
        new Thread(() -> {
            // The identity public key is what makes the account reachable at all. A placeholder in
            // its place would register an account whose peers can never derive a secret with it, so
            // registration stops here instead.
            final String pubKey = CryptoManager
                    .getInstance(requireActivity().getApplicationContext())
                    .getPublicKeyBase64();

            if (pubKey == null) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;

                        setLoading(false);
                        showError(getString(R.string.error_identity_key_unavailable));
                    });
                }

                return;
            }

            String token = connectionManager.register(username, password, pubKey);
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    setLoading(false);
                    if (!token.isEmpty()) {
                        // As on login: the password is used once and never stored.
                        new AccountStore(requireContext())
                                .saveSession(username, token, connectionManager.deployBaseUrl(), 0L);

                        // Cache the id the server just assigned to the account - the server list
                        // needs it to tell an administrator from a plain member.
                        connectionManager.fetchUserId(new ServerConnectionManager.Callback<>() {
                            @Override
                            public void onResult(Long userId) {
                                Log.d("RegisterFragment", "Cached user id " + userId);
                            }

                            @Override
                            public void onFailure(String error) {
                                Log.w("RegisterFragment", "Could not read the user id: " + error);
                            }
                        });

                        connectionManager.connect();
                        NavOptions navOptions = new NavOptions.Builder()
                                .setPopUpTo(R.id.nav_login, true)
                                .build();
                        navController.navigate(R.id.nav_transform, null, navOptions);
                    } else {
                        String lastErr = connectionManager.getLastRegisterError();
                        if (TextUtils.isEmpty(lastErr)) {
                            lastErr = getString(R.string.error_register_failed);
                        }
                        showError(lastErr);
                    }
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        if (binding == null) return;
        binding.progressbarRegister.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.buttonRegister.setEnabled(!loading);
        binding.buttonGoToLogin.setEnabled(!loading);
        binding.edittextUsername.setEnabled(!loading);
        binding.edittextPassword.setEnabled(!loading);
        binding.edittextConfirmPassword.setEnabled(!loading);
    }

    private void showError(String message) {
        if (binding == null) return;
        binding.textError.setText(message);
        binding.textError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (binding == null) return;
        binding.textError.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
