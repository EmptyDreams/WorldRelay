package top.kmar.worldrelay.server.auth;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.util.Arrays;
import java.util.Base64;

/** JDK Ed25519 密钥的 OpenSSH 序列化；不实现密码算法，不将私钥写入磁盘。 */
record SshKeyPair(String publicKey, String privateKey) {

    static SshKeyPair generate(String username, SecureRandom random) throws IOException {
        try {
            var generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(java.security.spec.NamedParameterSpec.ED25519, random);
            var pair = generator.generateKeyPair();
            var point = ((EdECPublicKey) pair.getPublic()).getPoint();
            byte[] y = point.getY().toByteArray();
            byte[] publicBytes = new byte[32];
            // RFC 8032：y 使用小端编码，最高位保存 x 的奇偶性。
            for (int index = 0; index < Math.min(y.length, 32); index++) {
                publicBytes[index] = y[y.length - 1 - index];
            }
            if (point.isXOdd()) publicBytes[31] |= (byte) 0x80;
            byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes()
                .orElseThrow(() -> new IOException("Ed25519 private key is unavailable"));
            if (seed.length != 32) throw new IOException("Unexpected Ed25519 key size");
            byte[] secret = new byte[64];
            try {
                System.arraycopy(seed, 0, secret, 0, 32);
                System.arraycopy(publicBytes, 0, secret, 32, 32);
                byte[] publicBlob = encode(output -> {
                    string(output, "ssh-ed25519");
                    bytes(output, publicBytes);
                });
                byte[] privateBlob = encode(output -> {
                    int check = random.nextInt();
                    output.writeInt(check);
                    output.writeInt(check);
                    string(output, "ssh-ed25519");
                    bytes(output, publicBytes);
                    bytes(output, secret);
                    string(output, username);
                    int padding = (8 - output.size() % 8) % 8;
                    for (int index = 1; index <= padding; index++) output.writeByte(index);
                });
                try {
                    byte[] container = encode(output -> {
                        output.write("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
                        string(output, "none");
                        string(output, "none");
                        bytes(output, new byte[0]);
                        output.writeInt(1);
                        bytes(output, publicBlob);
                        bytes(output, privateBlob);
                    });
                    try {
                        String pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                            + Base64.getMimeEncoder(70, new byte[]{'\n'}).encodeToString(container)
                            + "\n-----END OPENSSH PRIVATE KEY-----\n";
                        return new SshKeyPair("ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBlob)
                            + " " + username, pem);
                    } finally {
                        Arrays.fill(container, (byte) 0);
                    }
                } finally {
                    Arrays.fill(privateBlob, (byte) 0);
                }
            } finally {
                Arrays.fill(seed, (byte) 0);
                Arrays.fill(secret, (byte) 0);
            }
        } catch (GeneralSecurityException failure) {
            throw new IOException("Ed25519 key generation is unavailable", failure);
        }
    }

    private static byte[] encode(Encoder encoder) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(buffer)) {
            encoder.write(output);
        }
        return buffer.toByteArray();
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        bytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return "SshKeyPair[privateKey=<redacted>]";
    }

}