package com.example.voucherflow.fulfillment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 供应商接入配置，包含执行开关、并发额度、重试预算和协议凭证。 */
@Component
@ConfigurationProperties("voucherflow.fulfillment")
public class FulfillmentProperties {
    private boolean enabled;
    // 执行开关与新单准入分离，停止接收新单时仍可处理已受理订单。
    private boolean workerEnabled = true;
    private boolean acceptingNew = true;
    private int concurrency = 4;
    private int leaseSeconds = 30;
    private int maxAttempts = 12;
    private int maxElapsedSeconds = 3600;
    private int maxReservedPerCampaign = 1000;
    // 超过供应商承诺的幂等窗口后，履约任务只能查单。
    private int idempotencySeconds = 86400;
    private String supplierId = "simulator";
    private String contractVersion = "v1";
    private String baseUrl = "http://127.0.0.1:8090";
    private String apiToken = "";
    private String callbackSecret = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean v) { workerEnabled = v; }
    public boolean isAcceptingNew() { return acceptingNew; }
    public void setAcceptingNew(boolean v) { acceptingNew = v; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int v) { concurrency = v; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int v) { leaseSeconds = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { maxAttempts = v; }
    public int getMaxElapsedSeconds() { return maxElapsedSeconds; }
    public void setMaxElapsedSeconds(int v) { maxElapsedSeconds=v; }
    public int getMaxReservedPerCampaign() { return maxReservedPerCampaign; }
    public void setMaxReservedPerCampaign(int v) { maxReservedPerCampaign = v; }
    public int getIdempotencySeconds() { return idempotencySeconds; }
    public void setIdempotencySeconds(int v) { idempotencySeconds = v; }
    public String getSupplierId() { return supplierId; }
    public void setSupplierId(String v) { supplierId = v; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String v) { contractVersion = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String v) { apiToken = v; }
    public String getCallbackSecret() { return callbackSecret; }
    public void setCallbackSecret(String v) { callbackSecret = v; }
    @jakarta.annotation.PostConstruct
    void validate() {
        if (concurrency < 1 || concurrency > 32 || leaseSeconds < 10 || maxAttempts < 1
                || maxReservedPerCampaign < 1 || idempotencySeconds < 30 || maxElapsedSeconds < 1) {
            throw new IllegalArgumentException("Invalid fulfillment execution budget");
        }
        if (enabled && (apiToken.length() < 24 || callbackSecret.length() < 24)) {
            throw new IllegalArgumentException("Supplier API and callback secrets must contain at least 24 characters");
        }
    }
}
