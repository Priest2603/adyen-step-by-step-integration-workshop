package com.adyen.workshop.controllers;

import com.adyen.model.notification.NotificationRequest;
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import org.apache.coyote.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.SignatureException;

/**
 * REST controller for receiving Adyen webhook notifications
 */
@RestController
public class WebhookController {
    private final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ApplicationConfiguration applicationConfiguration;

    private final HMACValidator hmacValidator;

    @Autowired
    public WebhookController(ApplicationConfiguration applicationConfiguration, HMACValidator hmacValidator) {
        this.applicationConfiguration = applicationConfiguration;
        this.hmacValidator = hmacValidator;
    }

    // Step 16 - Validate the HMAC signature using the ADYEN_HMAC_KEY
    @PostMapping("/webhooks")
    public ResponseEntity<String> webhooks(@RequestBody String json) throws Exception {
        log.info("Received: {}", json);
        var notificationRequest = NotificationRequest.fromJson(json);
        var notificationRequestItem = notificationRequest.getNotificationItems().stream().findFirst();

        try {
            NotificationRequestItem item = notificationRequestItem.get();

            // Step 16 - Validate the HMAC signature using the ADYEN_HMAC_KEY
            if (!hmacValidator.validateHMAC(item, this.applicationConfiguration.getAdyenHmacKey())) {
                log.warn("Could not validate HMAC signature for incoming webhook message: {}", item);
                return ResponseEntity.unprocessableEntity().build();
            }

            // Success, log it for now
            log.info("Received webhook with event {}", item.toString());

            // Preauthorisation module - surface every webhook type used by the pre-auth flow.
            String eventCode = item.getEventCode();
            String psp = item.getPspReference();
            String originalPsp = item.getOriginalReference();
            boolean success = item.isSuccess();
            var amount = item.getAmount();
            String amountStr = amount != null ? amount.getValue() + " " + amount.getCurrency() : "n/a";

            switch (eventCode == null ? "" : eventCode.toUpperCase()) {
                case "AUTHORISATION":
                    log.info("*** AUTHORISATION webhook - success={} pspReference={} amount={} merchantReference={} ***",
                            success, psp, amountStr, item.getMerchantReference());
                    break;
                case "AUTHORISATION_ADJUSTMENT":
                    log.info("*** AUTHORISATION_ADJUSTMENT webhook - success={} pspReference={} originalReference={} amount={} ***",
                            success, psp, originalPsp, amountStr);
                    break;
                case "CAPTURE":
                    log.info("*** CAPTURE webhook - success={} pspReference={} originalReference={} amount={} ***",
                            success, psp, originalPsp, amountStr);
                    break;
                case "CAPTURE_FAILED":
                    log.info("*** CAPTURE_FAILED webhook - pspReference={} originalReference={} reason={} ***",
                            psp, originalPsp, item.getReason());
                    break;
                case "CANCELLATION":
                    log.info("*** CANCELLATION webhook - success={} pspReference={} originalReference={} ***",
                            success, psp, originalPsp);
                    break;
                case "TECHNICAL_CANCEL":
                    log.info("*** TECHNICAL_CANCEL webhook - success={} pspReference={} originalReference={} ***",
                            success, psp, originalPsp);
                    break;
                case "REFUND":
                    log.info("*** REFUND webhook - success={} pspReference={} originalReference={} amount={} ***",
                            success, psp, originalPsp, amountStr);
                    break;
                case "REFUND_FAILED":
                    log.info("*** REFUND_FAILED webhook - pspReference={} originalReference={} reason={} ***",
                            psp, originalPsp, item.getReason());
                    break;
                case "REFUNDED_REVERSED":
                    log.info("*** REFUNDED_REVERSED webhook - pspReference={} originalReference={} ***",
                            psp, originalPsp);
                    break;
                default:
                    log.info("Webhook eventCode={} pspReference={} success={}", eventCode, psp, success);
            }

            return ResponseEntity.accepted().build();
        } catch (SignatureException e) {
            // Handle invalid signature
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            // Handle all other errors
            return ResponseEntity.status(500).build();
        }
    }
}