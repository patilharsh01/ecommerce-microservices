package com.harshalpatil.ecommerce.auth.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RSA key pair tokens are signed with. Only this service ever holds the private key;
 * everyone else verifies with the public half published at {@code /.well-known/jwks.json}.
 * The key id is the RFC 7638 thumbprint, so it stays stable for a persisted key and changes
 * automatically when the key is rotated.
 */
@Component
@Slf4j
public class SigningKey {

    private static final String FILE_NAME = "jwt-signing-key.pem";

    private final RSAKey key;

    public SigningKey(TokenProperties properties) {
        this.key = toJwk(properties.keyDirectory() == null ? generate() : loadOrCreate(properties.keyDirectory()));
        log.info("JWT signing key ready (kid {})", key.getKeyID());
    }

    /** Public and private halves; never expose this outside the token encoder. */
    RSAKey privateJwk() {
        return key;
    }

    public RSAKey publicJwk() {
        return key.toPublicJWK();
    }

    private static KeyPair loadOrCreate(Path directory) {
        Path file = directory.resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                return read(file);
            }
            KeyPair generated = generate();
            Files.createDirectories(directory);
            write(file, generated);
            log.info("Generated a new JWT signing key at {}", file);
            return generated;
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read or write the JWT signing key at " + file, ex);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("RSA is not available", ex);
        }
    }

    private static KeyPair read(Path file) throws IOException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalStateException(file + " does not contain a PKCS#8 RSA private key", ex);
        }
    }

    private static void write(Path file, KeyPair keyPair) throws IOException {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX file system (e.g. Windows); rely on the directory's ACLs.
        }
    }

    private static RSAKey toJwk(KeyPair keyPair) {
        try {
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Cannot build a JWK from the signing key", ex);
        }
    }
}
