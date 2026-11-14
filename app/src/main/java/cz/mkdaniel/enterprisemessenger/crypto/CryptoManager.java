package cz.mkdaniel.enterprisemessenger.crypto;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.X509EncodedKeySpec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * This device's long-lived identity: an EC (secp256r1) key pair that lives inside the Android
 * KeyStore, plus the AES-GCM and ECDH primitives built on it.
 * <p>
 * The private half never leaves the keystore, so the only way to use it is to ask the platform for
 * a {@link KeyAgreement}. There is intentionally no software fallback: when the keystore cannot hand
 * out the key, {@link KeyStoreUnavailableException} is raised and the caller tells the user, because
 * silently generating a different key pair would replace his identity and make every ciphertext sent
 * under the old one unreadable - with nothing on screen to explain why.
 * <p>
 * Chat keys are not here: they live in {@link ChannelKeyRegistry} and {@link PeerSecretRegistry}.
 */
public class CryptoManager {

    private static final String TAG = "CryptoManager";

    private static final String KEY_ALIAS = "EnterpriseMessenger_EC_Key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String EC_CURVE = "secp256r1";

    private static final String AES_GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private static volatile CryptoManager instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile KeyPair keyPair;

    /** Reports a key pair that could not be created or loaded. */
    public interface KeyInitListener {
	void onKeyInitFailed(KeyStoreUnavailableException failure);
    }

    private CryptoManager() {
    }

    public static CryptoManager getInstance(Context context) {
	if (instance == null) {
	    synchronized (CryptoManager.class) {
		if (instance == null) {
		    instance = new CryptoManager();
		}
	    }
	}

	return instance;
    }

    /**
     * Creates or loads the identity key pair off the main thread. The first call on a fresh install
     * touches the keystore daemon, which is slow enough to be worth moving off the UI thread.
     *
     * @param listener receives a failure, or {@code null} when the caller only wants the key warm
     */
    public void initializeKeysAsync(final KeyInitListener listener) {
	executor.execute(() -> {
	    try {
		getOrCreateKeyPair();
	    } catch (KeyStoreUnavailableException failure) {
		Log.e(TAG, "Identity key pair is unavailable", failure);

		if (listener != null) {
		    listener.onKeyInitFailed(failure);
		}
	    }
	});
    }

    /**
     * The identity key pair: loaded from the KeyStore, generated into it on first run.
     *
     * @throws KeyStoreUnavailableException when the key cannot be created or read. Never answered
     *                                      with a software-generated pair.
     */
    public synchronized KeyPair getOrCreateKeyPair() throws KeyStoreUnavailableException {
	if (keyPair != null) {
	    return keyPair;
	}

	try {
	    final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
	    keyStore.load(null);

	    if (!keyStore.containsAlias(KEY_ALIAS)) {
		Log.d(TAG, "No identity key in KeyStore, generating an EC key pair");

		keyPair = generateIdentityKeyPair();

		return keyPair;
	    }

	    final KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
	    if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
		// The alias exists but is not a private-key entry: a foreign entry under our name.
		// Regenerating would be a silent identity change, so this is an error instead.
		throw new KeyStoreUnavailableException(
			"KeyStore alias " + KEY_ALIAS + " does not hold a private key entry");
	    }

	    final KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) entry;
	    final PrivateKey privateKey = privateKeyEntry.getPrivateKey();
	    final PublicKey publicKey = privateKeyEntry.getCertificate().getPublicKey();

	    Log.d(TAG, "Loaded the identity key pair from KeyStore");

	    keyPair = new KeyPair(publicKey, privateKey);

	    return keyPair;
	} catch (GeneralSecurityException securityException) {
	    throw new KeyStoreUnavailableException("Identity key pair could not be loaded from KeyStore",
		    securityException);
	} catch (java.io.IOException ioException) {
	    throw new KeyStoreUnavailableException("AndroidKeyStore could not be loaded", ioException);
	}
    }

    /**
     * Like {@link #getOrCreateKeyPair()} but {@code null} on failure, for the paths that already
     * have to cope with a missing key (registration, the startup handshake).
     */
    public KeyPair tryGetKeyPair() {
	try {
	    return getOrCreateKeyPair();
	} catch (KeyStoreUnavailableException failure) {
	    Log.e(TAG, "Identity key pair is unavailable: " + failure.getMessage());

	    return null;
	}
    }

    private KeyPair generateIdentityKeyPair() throws GeneralSecurityException {
	final KeyPairGenerator keyPairGenerator =
		KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);

	keyPairGenerator.initialize(new KeyGenParameterSpec
		.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY | KeyProperties.PURPOSE_SIGN
			| KeyProperties.PURPOSE_VERIFY)
		.setAlgorithmParameterSpec(new ECGenParameterSpec(EC_CURVE))
		.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
		.build());

	final KeyPair generated = keyPairGenerator.generateKeyPair();

	Log.d(TAG, "Identity key pair generated into KeyStore");

	return generated;
    }

    /**
     * Deletes this device's identity key pair from the KeyStore, for the one action that has to end
     * an identity: the user is handing the device to another account (see the reset in Settings).
     * <p>
     * Every ciphertext this device sealed under the old pair becomes unreadable, which is the point:
     * one device holding two accounts' keys lets both of them read each other's channels, because
     * channel ids are global. Callers are expected to empty the sealed registries
     * ({@link SecureBlobStore#clearAll()}) in the same action.
     *
     * @return {@code true} when the alias is gone from the KeyStore afterwards
     */
    public synchronized boolean deleteIdentityKey() {
	try {
	    final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
	    keyStore.load(null);

	    if (keyStore.containsAlias(KEY_ALIAS)) {
		keyStore.deleteEntry(KEY_ALIAS);
	    }

	    // The cached pair is a handle into the entry that has just been deleted; the next call has
	    // to observe a fresh identity rather than reuse the removed one.
	    keyPair = null;

	    Log.d(TAG, "Deleted the identity key pair from KeyStore");

	    return !keyStore.containsAlias(KEY_ALIAS);
	} catch (GeneralSecurityException securityException) {
	    Log.e(TAG, "The identity key pair could not be deleted", securityException);

	    return false;
	} catch (java.io.IOException ioException) {
	    Log.e(TAG, "AndroidKeyStore could not be loaded", ioException);

	    return false;
	}
    }

    public PublicKey getPublicKey() {
	final KeyPair pair = tryGetKeyPair();

	return pair == null ? null : pair.getPublic();
    }

    /**
     * The identity public key in the X.509 encoding the server stores ({@code user_key.public_key}),
     * or {@code null} when the identity key is unavailable. Callers must treat {@code null} as a hard
     * failure: registering without a public key would create an account nobody can talk to.
     */
    public String getPublicKeyBase64() {
	final PublicKey publicKey = getPublicKey();
	if (publicKey == null) {
	    return null;
	}

	return Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * Opens an AES-GCM payload of the form {@code [12-byte IV][ciphertext + tag]}.
     */
    public byte[] decryptPayload(byte[] encryptedBytes, byte[] aesKeyBytes) throws GeneralSecurityException {
	if (encryptedBytes == null || encryptedBytes.length <= IV_LENGTH_BYTES) {
	    throw new IllegalArgumentException("Invalid encrypted payload length");
	}
	if (aesKeyBytes == null || aesKeyBytes.length == 0) {
	    throw new IllegalArgumentException("An empty key cannot open a payload");
	}

	final Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORM);
	cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKeyBytes, "AES"),
		new GCMParameterSpec(TAG_LENGTH_BITS, encryptedBytes, 0, IV_LENGTH_BYTES));

	// A wrong candidate key surfaces as AEADBadTagException, which callers that try several keys
	// catch and treat as "this is not the key for this message".
	return cipher.doFinal(encryptedBytes, IV_LENGTH_BYTES, encryptedBytes.length - IV_LENGTH_BYTES);
    }

    /**
     * Seals {@code plainBytes} with AES-GCM under a fresh random IV.
     * Output format: {@code [12-byte IV][ciphertext + tag]}.
     */
    public byte[] encryptPayload(final byte[] plainBytes, final byte[] aesKeyBytes) throws GeneralSecurityException {
	if (aesKeyBytes == null || aesKeyBytes.length == 0) {
	    throw new IllegalArgumentException("An empty key cannot seal a payload");
	}

	final byte[] iv = new byte[IV_LENGTH_BYTES];
	new SecureRandom().nextBytes(iv);

	final SecretKey secretKey = new SecretKeySpec(aesKeyBytes, "AES");
	final Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORM);
	cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

	final byte[] cipherText = cipher.doFinal(plainBytes);
	final byte[] result = new byte[IV_LENGTH_BYTES + cipherText.length];

	System.arraycopy(iv, 0, result, 0, IV_LENGTH_BYTES);
	System.arraycopy(cipherText, 0, result, IV_LENGTH_BYTES, cipherText.length);

	return result;
    }

    /**
     * ECDH with a peer's public key, hashed with SHA-256 into a 256-bit AES key.
     * <p>
     * The peer key comes from the server, which is not a trust anchor for it (audit H-7/H-8), so it
     * is validated before the private key is used with it: the parameters must be exactly
     * {@value #EC_CURVE} and the point must satisfy the curve equation. Without that check a crafted
     * key could place the agreement in a small subgroup and reveal this device's private scalar.
     *
     * @param remotePublicKeyBytes the peer key in X.509 encoding, as {@code GET /api/channels/{id}/keys}
     *                             returns it
     * @throws GeneralSecurityException when the key is not a usable secp256r1 public key
     */
    public byte[] deriveSharedSecret(byte[] remotePublicKeyBytes) throws GeneralSecurityException {
	if (remotePublicKeyBytes == null || remotePublicKeyBytes.length == 0) {
	    throw new IllegalArgumentException("Cannot derive a secret from an empty peer key");
	}

	final PublicKey remotePublicKey = validatePeerPublicKey(remotePublicKeyBytes);

	final KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
	keyAgreement.init(getOrCreateKeyPair().getPrivate());
	keyAgreement.doPhase(remotePublicKey, true);

	final byte[] agreement = keyAgreement.generateSecret();

	// The raw X coordinate has a fixed 32-byte width with leading zeros, which is not uniformly
	// distributed; hashing it yields a full-entropy AES key and matches what the other side does.
	return MessageDigest.getInstance("SHA-256").digest(agreement);
    }

    /**
     * Decodes a peer key and rejects anything that is not a point on {@value #EC_CURVE}.
     */
    private PublicKey validatePeerPublicKey(byte[] encodedKey) throws GeneralSecurityException {
	final KeyFactory keyFactory = KeyFactory.getInstance("EC");
	final PublicKey decoded = keyFactory.generatePublic(new X509EncodedKeySpec(encodedKey));

	if (!(decoded instanceof ECPublicKey)) {
	    throw new GeneralSecurityException("Peer key is not an EC public key");
	}

	final ECPublicKey peerKey = (ECPublicKey) decoded;
	final ECParameterSpec expected = referenceCurve();
	final ECParameterSpec actual = peerKey.getParams();

	if (actual == null
		|| !actual.getCurve().equals(expected.getCurve())
		|| !actual.getGenerator().equals(expected.getGenerator())
		|| actual.getOrder().compareTo(expected.getOrder()) != 0
		|| actual.getCofactor() != expected.getCofactor()) {
	    throw new GeneralSecurityException("Peer key is not on " + EC_CURVE);
	}

	assertPointOnCurve(peerKey.getW(), actual);

	return decoded;
    }

    /**
     * Verifies {@code y^2 == x^3 + a*x + b (mod p)} and that both coordinates are in range. The
     * provider may hand back an unvalidated point, so the equation is checked explicitly.
     */
    private static void assertPointOnCurve(ECPoint point, ECParameterSpec parameters)
	    throws GeneralSecurityException {
	if (point == null || ECPoint.POINT_INFINITY.equals(point)) {
	    throw new GeneralSecurityException("Peer key is the point at infinity");
	}

	final ECField field = parameters.getCurve().getField();
	if (!(field instanceof ECFieldFp)) {
	    throw new GeneralSecurityException("Only prime-field curves are supported");
	}

	final BigInteger prime = ((ECFieldFp) field).getP();
	final BigInteger a = parameters.getCurve().getA();
	final BigInteger b = parameters.getCurve().getB();

	final BigInteger x = point.getAffineX();
	final BigInteger y = point.getAffineY();

	if (x == null || y == null
		|| x.signum() < 0 || x.compareTo(prime) >= 0
		|| y.signum() < 0 || y.compareTo(prime) >= 0) {
	    throw new GeneralSecurityException("Peer key coordinates are out of range");
	}

	final BigInteger leftSide = y.modPow(BigInteger.TWO, prime);
	final BigInteger rightSide = x.modPow(BigInteger.valueOf(3), prime)
		.add(a.multiply(x))
		.add(b)
		.mod(prime);

	if (!leftSide.equals(rightSide)) {
	    throw new GeneralSecurityException("Peer key is not a point on " + EC_CURVE);
	}

	// The peer scalar must also be a multiple of the group order, otherwise the agreement lands
	// in a small subgroup. Multiplying by the order must give the identity, which ECPoint cannot
	// answer on its own; the cheap and sufficient equivalent here is that the order is prime for
	// secp256r1, so any on-curve point other than infinity is already in the right subgroup.
	if (!parameters.getOrder().isProbablePrime(64)) {
	    throw new GeneralSecurityException("Unexpected curve order");
	}
    }

    private static ECParameterSpec referenceCurve() throws GeneralSecurityException {
	final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
	parameters.init(new ECGenParameterSpec(EC_CURVE));

	return parameters.getParameterSpec(ECParameterSpec.class);
    }

    /**
     * SHA-256 of {@code input}, for the few places that need a digest of key material without each
     * of them instantiating a {@link MessageDigest}.
     */
    public static byte[] sha256(byte[] input) throws GeneralSecurityException {
	return MessageDigest.getInstance("SHA-256").digest(input);
    }

    /**
     * Decodes a peer public key that arrives as Base64 (the shape {@code /api/channels/{id}/keys}
     * and {@code /api/users/{id}/key} return).
     */
    public static byte[] decodePeerKey(String base64Key) {
	if (base64Key == null || base64Key.isEmpty()) {
	    return null;
	}

	try {
	    return Base64.decode(base64Key, Base64.NO_WRAP);
	} catch (IllegalArgumentException malformed) {
	    Log.w(TAG, "Peer public key is not valid Base64");

	    return null;
	}
    }
}
