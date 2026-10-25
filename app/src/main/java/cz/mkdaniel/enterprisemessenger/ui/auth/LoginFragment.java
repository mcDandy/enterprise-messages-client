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
import cz.mkdaniel.enterprisemessenger.databinding.FragmentLoginBinding;
import cz.mkdaniel.enterprisemessenger.net.AccountStore;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

public class LoginFragment extends Fragment {

    private FragmentLoginBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = NavHostFragment.findNavController(this);

        binding.buttonLogin.setOnClickListener(v -> performLogin(navController));

        binding.buttonGoToRegister.setOnClickListener(v -> navController.navigate(R.id.nav_register));
    }

    private void performLogin(NavController navController) {
        String username = binding.edittextUsername.getText() != null ? binding.edittextUsername.getText().toString().trim() : "";
        String password = binding.edittextPassword.getText() != null ? binding.edittextPassword.getText().toString().trim() : "";

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            showError(getString(R.string.error_empty_fields));
            return;
        }

        hideError();
        setLoading(true);

        ServerConnectionManager connectionManager = ServerConnectionManager.getInstance(requireContext());
        new Thread(() -> {
            String token = connectionManager.login(connectionManager.getCurrentServerIp(), username, password);
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
                        showError(getString(R.string.error_login_failed));
                    }
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        if (binding == null) return;
        binding.progressbarLogin.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.buttonLogin.setEnabled(!loading);
        binding.buttonGoToRegister.setEnabled(!loading);
        binding.edittextUsername.setEnabled(!loading);
        binding.edittextPassword.setEnabled(!loading);
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
