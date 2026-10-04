package io.souqly.seller.application;

import io.souqly.platform.storage.ObjectStorage;
import io.souqly.platform.storage.S3Settings;
import io.souqly.seller.config.OnboardingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * KYC documents in a private bucket: {@code uploads/} receives direct browser uploads (abandoned
 * ones expire after a day), {@code documents/} holds verified files. Nothing is publicly readable;
 * reviewers get short-lived signed download URLs.
 */
@Component
public class DocumentStorage implements SmartInitializingSingleton, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);
    static final String UPLOADS = "uploads/";

    private final ObjectStorage store;

    public DocumentStorage(OnboardingProperties properties) {
        var config = properties.storage();
        this.store = new ObjectStorage(new S3Settings(config.endpoint(), config.publicEndpoint(), config.region(),
                config.accessKey(), config.secretKey(), config.bucket()));
    }

    public ObjectStorage store() {
        return store;
    }

    static String uploadKey(ApplicationDocument document) {
        return UPLOADS + document.applicationId() + "/" + document.id();
    }

    static String verifiedKey(ApplicationDocument document) {
        return "documents/" + document.applicationId() + "/" + document.id();
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            store.ensureBucket();
            store.expire(UPLOADS, 1);
        }
        catch (RuntimeException ex) {
            log.error("Could not prepare document bucket {}: {}", store.bucket(), ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        store.close();
    }
}
