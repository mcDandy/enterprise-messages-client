package cz.mkdaniel.enterprisemessenger.crypto;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Manages Elliptic Curve (EC) Cryptography and Android KeyStore operations.
 * <p>
 * Generates an EC (secp256r1) key pair on first app start using Android KeyStore.
 * Provides methods for ECDH key exchange, AES-GCM payload encryption, and decryption.
 */
public class CryptoManager {

    private static final String TAG = "CryptoManager";
    private static final String KEY_ALIAS = "EnterpriseMessenger_EC_Key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String EC_CURVE = "secp256r1";

    private static volatile CryptoManager instance;
    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private KeyPair keyPair;

    private CryptoManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static CryptoManager getInstance(Context context) {
        if (instance == null) {
            synchronized (CryptoManager.class) {
                if (instance == null) {
                    instance = new CryptoManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * Initializes or loads the EC key pair asynchronously on first app launch.
     */
    public void initializeKeysAsync() {
        executor.execute(this::getOrCreateKeyPair);
    }

    /**
     * Retrieves the EC KeyPair from Android KeyStore or generates a new one on first run.
     */
    public synchronized KeyPair getOrCreateKeyPair() {
        if (keyPair != null) {
            return keyPair;
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                Log.d(TAG, "Generating new Elliptic Curve (EC) key pair on first launch...");
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);

                KeyGenParameterSpec parameterSpec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_AGREE_KEY | KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec(EC_CURVE))
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .build();

                keyPairGenerator.initialize(parameterSpec);
                keyPair = keyPairGenerator.generateKeyPair();
                Log.d(TAG, "EC key pair generated successfully!");
            } else {
                Log.d(TAG, "Loading existing EC key pair from KeyStore...");
                KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
                PrivateKey privateKey = privateKeyEntry.getPrivateKey();
                PublicKey publicKey = privateKeyEntry.getCertificate().getPublicKey();
                keyPair = new KeyPair(publicKey, privateKey);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing EC key pair in KeyStore, falling back to software key generator", e);
            generateSoftwareKeyPair();
        }

        return keyPair;
    }

    private void generateSoftwareKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
            keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
            keyPair = keyPairGenerator.generateKeyPair();
            Log.d(TAG, "Software EC key pair generated successfully!");
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate software EC key pair", e);
        }
    }

    public PublicKey getPublicKey() {
        KeyPair kp = getOrCreateKeyPair();
        return kp != null ? kp.getPublic() : null;
    }

    public String getPublicKeyBase64() {
        PublicKey pk = getPublicKey();
        if (pk == null) return null;
        return Base64.encodeToString(pk.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * Decrypts AES-GCM payload using symmetric key or private key derived secret.
     * Expected payload format: [12 bytes IV] + [AES-GCM Ciphertext + Auth Tag]
     */
    public byte[] decryptPayload(byte[] encryptedBytes, byte[] aesKeyBytes) throws Exception {
        if (encryptedBytes == null || encryptedBytes.length < 12) {
            throw new IllegalArgumentException("Invalid encrypted payload length");
        }

        byte[] iv = new byte[12];
        System.arraycopy(encryptedBytes, 0, iv, 0, 12);

        int cipherTextLen = encryptedBytes.length - 12;
        byte[] cipherText = new byte[cipherTextLen];
        System.arraycopy(encryptedBytes, 12, cipherText, 0, cipherTextLen);

        SecretKey secretKey = new SecretKeySpec(aesKeyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        return cipher.doFinal(cipherText);
    }

    /**
     * Encrypts plain data using AES-GCM and prepends a 12-byte IV.
     * Output format: [12 bytes IV] + [AES-GCM Ciphertext + Auth Tag]
     */
    public byte[] encryptPayload(byte[] plainBytes, byte[] aesKeyBytes) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        SecretKey secretKey = new SecretKeySpec(aesKeyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

        byte[] cipherText = cipher.doFinal(plainBytes);

        byte[] result = new byte[12 + cipherText.length];
        System.arraycopy(iv, 0, result, 0, 12);
        System.arraycopy(cipherText, 0, result, 12, cipherText.length);

        return result;
    }

    /**
     * Performs ECDH (Elliptic Curve Diffie-Hellman) key agreement with a remote public key
     * to derive a shared 256-bit AES secret key.
     */
    public byte[] deriveSharedSecret(byte[] remotePublicKeyBytes) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(remotePublicKeyBytes);
        PublicKey remotePublicKey = keyFactory.generatePublic(keySpec);

        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(getOrCreateKeyPair().getPrivate());
        keyAgreement.doPhase(remotePublicKey, true);

        byte[] sharedSecret = keyAgreement.generateSecret();
        // SHA-256 hash to get a 256-bit key for AES
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return sha256.digest(sharedSecret);
    }
}
