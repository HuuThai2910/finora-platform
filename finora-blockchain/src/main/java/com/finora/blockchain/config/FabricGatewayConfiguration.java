package com.finora.blockchain.config;

import com.finora.blockchain.integration.proof.fabric.FabricLedgerClient;
import com.finora.blockchain.integration.proof.fabric.GatewayFabricLedgerClient;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kết nối Fabric Gateway chỉ khi operator chọn {@code HYPERLEDGER_FABRIC} (W3D, D53). Thiếu hoặc hỏng file danh tính
 * là cấu hình sai nên service dừng khởi động; mạng Fabric tạm tắt thì service vẫn chạy, proof chờ retry.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "finora.blockchain.proof.provider", havingValue = "HYPERLEDGER_FABRIC")
public class FabricGatewayConfiguration {

    /** Gateway không đóng channel được truyền vào; Spring hủy Gateway (phụ thuộc channel) trước rồi mới tới channel. */
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel fabricGrpcChannel(FabricGatewayProperties properties) {
        Path tlsCertificate = requireFile(properties.tlsCertPath(), "FABRIC_TLS_CERT_PATH");
        try {
            NettyChannelBuilder builder = NettyChannelBuilder.forTarget(properties.peerEndpoint())
                    .sslContext(GrpcSslContexts.forClient().trustManager(tlsCertificate.toFile()).build());
            if (properties.peerHostOverride() != null) {
                builder.overrideAuthority(properties.peerHostOverride());
            }
            return builder.build();
        } catch (SSLException exception) {
            throw new IllegalStateException("Không đọc được TLS CA của peer Fabric (FABRIC_TLS_CERT_PATH)", exception);
        }
    }

    @Bean(destroyMethod = "close")
    Gateway fabricGateway(ManagedChannel fabricGrpcChannel, FabricGatewayProperties properties) {
        Identity identity = new X509Identity(properties.mspId(),
                readCertificate(requireFile(properties.certPath(), "FABRIC_CERT_PATH")));
        Signer signer = Signers.newPrivateKeySigner(readPrivateKey(requireFile(properties.keyPath(), "FABRIC_KEY_PATH")));
        Gateway gateway = Gateway.newInstance()
                .identity(identity)
                .signer(signer)
                .connection(fabricGrpcChannel)
                .evaluateOptions(options -> withDeadline(options, properties.evaluateTimeout()))
                .endorseOptions(options -> withDeadline(options, properties.endorseTimeout()))
                .submitOptions(options -> withDeadline(options, properties.submitTimeout()))
                .commitStatusOptions(options -> withDeadline(options, properties.commitStatusTimeout()))
                .connect();
        log.info("Kết nối Fabric Gateway: endpoint={}, mspId={}, channel={}, chaincode={}",
                properties.peerEndpoint(), properties.mspId(), properties.channelName(), properties.chaincodeName());
        return gateway;
    }

    @Bean
    FabricLedgerClient fabricLedgerClient(Gateway fabricGateway, FabricGatewayProperties properties) {
        return new GatewayFabricLedgerClient(fabricGateway.getNetwork(properties.channelName()), properties.chaincodeName());
    }

    private static io.grpc.CallOptions withDeadline(io.grpc.CallOptions options, Duration timeout) {
        return options.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Path requireFile(String path, String variable) {
        if (path == null) {
            throw new IllegalStateException("Thiếu " + variable + " khi BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC");
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalStateException(variable + " không trỏ tới file đọc được; chạy docker/fabric-network/fabric-network.ps1 -Action Up");
        }
        return file;
    }

    private static X509Certificate readCertificate(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return Identities.readX509Certificate(reader);
        } catch (IOException | CertificateException exception) {
            throw new IllegalStateException("FABRIC_CERT_PATH không phải chứng thư X.509 PEM", exception);
        }
    }

    private static PrivateKey readPrivateKey(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return Identities.readPrivateKey(reader);
        } catch (IOException | InvalidKeyException exception) {
            // Không đưa nội dung khóa vào thông điệp lỗi.
            throw new IllegalStateException("FABRIC_KEY_PATH không phải khóa riêng PEM", exception);
        }
    }
}
