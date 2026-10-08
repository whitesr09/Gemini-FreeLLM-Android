package com.nshd.geminifreellm.ui;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import android.security.keystore.KeyGenParameterSpec;

/** In-memory test provider. It exercises app startup without claiming hardware Keystore coverage. */
public final class TestKeyStore extends Provider {
    static final Map<String, Key> keys = new ConcurrentHashMap<>();
    public TestKeyStore() {
        super("AndroidKeyStore", 1.0, "Synthetic test-only keystore");
        put("KeyStore.AndroidKeyStore", Store.class.getName());
        put("KeyGenerator.AES", Generator.class.getName());
    }
    public static class Store extends KeyStoreSpi {
        public Key engineGetKey(String alias, char[] password) { return keys.get(alias); }
        public Certificate[] engineGetCertificateChain(String alias) { return null; }
        public Certificate engineGetCertificate(String alias) { return null; }
        public Date engineGetCreationDate(String alias) { return new Date(0); }
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { keys.put(alias, key); }
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { throw new UnsupportedOperationException(); }
        public void engineSetCertificateEntry(String alias, Certificate cert) { throw new UnsupportedOperationException(); }
        public void engineDeleteEntry(String alias) { keys.remove(alias); }
        public Enumeration<String> engineAliases() { return Collections.enumeration(keys.keySet()); }
        public boolean engineContainsAlias(String alias) { return keys.containsKey(alias); }
        public int engineSize() { return keys.size(); }
        public boolean engineIsKeyEntry(String alias) { return keys.containsKey(alias); }
        public boolean engineIsCertificateEntry(String alias) { return false; }
        public String engineGetCertificateAlias(Certificate cert) { return null; }
        public void engineStore(OutputStream stream, char[] password) { }
        public void engineLoad(InputStream stream, char[] password) { }
    }
    public static class Generator extends KeyGeneratorSpi {
        private String alias;
        public void engineInit(SecureRandom random) { }
        public void engineInit(int keySize, SecureRandom random) { }
        public void engineInit(AlgorithmParameterSpec params, SecureRandom random) {
            alias = ((KeyGenParameterSpec) params).getKeystoreAlias();
        }
        public SecretKey engineGenerateKey() {
            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES", "SunJCE");
                generator.init(256);
                SecretKey key = generator.generateKey();
                keys.put(alias, key);
                return key;
            } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
        }
    }
}
