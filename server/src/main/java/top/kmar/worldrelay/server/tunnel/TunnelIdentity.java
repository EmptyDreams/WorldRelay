package top.kmar.worldrelay.server.tunnel;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

/** TLS 私钥仅在服务端保留；使用 Java 库生成证书，不执行 keytool 或 openssl。 */
final class TunnelIdentity {

    final SslContext context;
    final String fingerprint;

    TunnelIdentity(Path directory) throws IOException {
        try {
            directory = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(directory)) throw new IOException("TLS directory must not be a symbolic link");
            Files.createDirectories(directory);
            boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
            if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Path file = directory.resolve("identity.p12");
            if (Files.isSymbolicLink(file)) throw new IOException("TLS identity must not be a symbolic link");
            KeyStore store = KeyStore.getInstance("PKCS12");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    store.load(input, new char[0]);
                }
            } else {
                store.load(null, new char[0]);
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                Instant now = Instant.now();
                X500Name subject = new X500Name("CN=WorldRelay tunnel");
                var builder = new JcaX509v3CertificateBuilder(subject, new BigInteger(160, new SecureRandom()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(3650, ChronoUnit.DAYS)), subject, pair.getPublic());
                X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
                store.setKeyEntry("tunnel", pair.getPrivate(), new char[0], new java.security.cert.Certificate[]{certificate});
                Path temporary = posix ? Files.createTempFile(directory, ".identity-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(directory, ".identity-", ".tmp");
                try {
                    try (OutputStream output = Files.newOutputStream(temporary)) {
                        store.store(output, new char[0]);
                    }
                    try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        output.force(true);
                    }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if (posix) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            X509Certificate certificate = (X509Certificate) store.getCertificate("tunnel");
            certificate.checkValidity();
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            context = SslContextBuilder.forServer((PrivateKey) store.getKey("tunnel", new char[0]), certificate).sslProvider(SslProvider.JDK).build();
        } catch (Exception failure) {
            throw new IOException("Unable to initialize tunnel TLS identity", failure);
        }
    }

}