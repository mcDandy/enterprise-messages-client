package cz.mkdaniel.enterprisemessenger.ui.auth;

import android.os.Bundle;
import android.text.TextUtils;
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

        binding.buttonGoToLogin.setOnClickListener(v -> navController.navigate(R.id.nav_login));
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

        hideError();
        setLoading(true);

        ServerConnectionManager connectionManager = ServerConnectionManager.getInstance(requireContext());
        new Thread(() -> {
            String pubKey = CryptoManager.getInstance(requireContext()).getPublicKeyBase64();
            if (pubKey == null) pubKey = "dummyPublicKeyBase64StringForE2EE";
            String token = connectionManager.register(connectionManager.getCurrentServerIp(), username, password, pubKey);
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    setLoading(false);
                    if (!token.isEmpty()) {
                        new AccountStore(requireContext()).saveSession(username, password, token);
                        connectionManager.connectToServer(connectionManager.getCurrentServerIp());
                        NavOptions navOptions = new NavOptions.Builder()
                                .setPopUpTo(R.id.nav_login, true)
                                .build();
                        navController.navigate(R.id.nav_transform, null, navOptions);
                    } else {
                        showError(getString(R.string.error_register_failed));
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
