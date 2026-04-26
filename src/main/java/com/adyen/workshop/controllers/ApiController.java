package com.adyen.workshop.controllers;

import com.adyen.model.RequestOptions;
import com.adyen.model.checkout.*;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.service.checkout.ModificationsApi;
import com.adyen.service.checkout.PaymentsApi;
import com.adyen.service.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for using the Adyen payments API.
 */
@RestController
public class ApiController {
    private final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final ApplicationConfiguration applicationConfiguration;
    private final PaymentsApi paymentsApi;
    private final ModificationsApi modificationsApi;

    public ApiController(ApplicationConfiguration applicationConfiguration, PaymentsApi paymentsApi, ModificationsApi modificationsApi) {
        this.applicationConfiguration = applicationConfiguration;
        this.paymentsApi = paymentsApi;
        this.modificationsApi = modificationsApi;
    }

    // Step 0
    @GetMapping("/hello-world")
    public ResponseEntity<String> helloWorld() throws Exception {
        return ResponseEntity.ok().body("This is the 'Hello World' from the workshop - You've successfully finished step 0!");
    }

    // Step 7
    @PostMapping("/api/paymentMethods")
    public ResponseEntity<PaymentMethodsResponse> paymentMethods() throws IOException, ApiException {
        var paymentMethodsRequest = new PaymentMethodsRequest();
        paymentMethodsRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());

        log.info("Retrieving available Payment Methods from Adyen {}", paymentMethodsRequest);
        var response = paymentsApi.paymentMethods(paymentMethodsRequest);
        log.info("Payment Methods response from Adyen {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation module - /payments call with PreAuth flag and manual capture (captureDelayHours = -1).
    // Amount is 10 USD (1000 minor units). The pspReference returned is the one used for
    // /api/adjustAuthorisation, /api/capture, /api/cancel and /api/refund below.
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> payments(@RequestBody PaymentRequest body) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("USD")
                .value(1000L); // 10 USD pre-auth
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("https://ominous-barnacle-454xrw54jgf7xwp-8080.app.github.dev/handleShopperRedirect");

        // PreAuth flag + manual capture so we can adjust/capture/cancel/refund later.
        var additionalData = new HashMap<String, String>();
        additionalData.put("authorisationType", "PreAuth");
        additionalData.put("manualCapture", "true");
        paymentRequest.setAdditionalData(additionalData);
        paymentRequest.setCaptureDelayHours(-1); // -1 = manual capture; overrides Customer Area capture-delay config.

        // 3DS2 Redirect support - keep so the drop-in can finish challenges if the issuer requests one.
        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("https://ominous-barnacle-454xrw54jgf7xwp-8080.app.github.dev");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("PreAuth PaymentsRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("PreAuth PaymentsResponse {}", response);
        log.info("*** PreAuth completed - pspReference={} amount={} {} resultCode={} ***",
                response.getPspReference(),
                response.getAmount() != null ? response.getAmount().getValue() : null,
                response.getAmount() != null ? response.getAmount().getCurrency() : null,
                response.getResultCode());

        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation module - bump authorised amount (async). Default target 66 USD (6600 minor units).
    // curl -X POST "https://<host>/api/adjustAuthorisation/{pspReference}?value=6600&currency=USD"
    @PostMapping("/api/adjustAuthorisation/{pspReference}")
    public ResponseEntity<PaymentAmountUpdateResponse> adjustAuthorisation(
            @PathVariable String pspReference,
            @RequestParam(defaultValue = "6600") Long value,
            @RequestParam(defaultValue = "USD") String currency) throws IOException, ApiException {

        var request = new PaymentAmountUpdateRequest();
        request.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        request.setAmount(new Amount().currency(currency).value(value));
        request.setReference(UUID.randomUUID().toString());
        request.setIndustryUsage(PaymentAmountUpdateRequest.IndustryUsageEnum.DELAYEDCHARGE);

        log.info("AdjustAuthorisation request pspReference={} value={} {}", pspReference, value, currency);
        var response = modificationsApi.updateAuthorisedAmount(pspReference, request);
        log.info("*** AdjustAuthorisation response pspReference={} status={} ***",
                response.getPspReference(), response.getStatus());
        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation module - capture the pre-authorised payment (default 66 USD to match the adjustment).
    // curl -X POST "https://<host>/api/capture/{pspReference}?value=6600&currency=USD"
    @PostMapping("/api/capture/{pspReference}")
    public ResponseEntity<PaymentCaptureResponse> capture(
            @PathVariable String pspReference,
            @RequestParam(defaultValue = "6600") Long value,
            @RequestParam(defaultValue = "USD") String currency) throws IOException, ApiException {

        var request = new PaymentCaptureRequest();
        request.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        request.setAmount(new Amount().currency(currency).value(value));
        request.setReference(UUID.randomUUID().toString());

        log.info("Capture request pspReference={} value={} {}", pspReference, value, currency);
        var response = modificationsApi.captureAuthorisedPayment(pspReference, request);
        log.info("*** Capture response pspReference={} status={} ***",
                response.getPspReference(), response.getStatus());
        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation module - cancel a pre-authorised payment (before capture).
    // curl -X POST "https://<host>/api/cancel/{pspReference}"
    @PostMapping("/api/cancel/{pspReference}")
    public ResponseEntity<PaymentCancelResponse> cancel(@PathVariable String pspReference) throws IOException, ApiException {
        var request = new PaymentCancelRequest();
        request.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        request.setReference(UUID.randomUUID().toString());

        log.info("Cancel request pspReference={}", pspReference);
        var response = modificationsApi.cancelAuthorisedPaymentByPspReference(pspReference, request);
        log.info("*** Cancel response pspReference={} status={} ***",
                response.getPspReference(), response.getStatus());
        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation module - refund a captured payment.
    // curl -X POST "https://<host>/api/refund/{pspReference}?value=6600&currency=USD"
    @PostMapping("/api/refund/{pspReference}")
    public ResponseEntity<PaymentRefundResponse> refund(
            @PathVariable String pspReference,
            @RequestParam(defaultValue = "6600") Long value,
            @RequestParam(defaultValue = "USD") String currency) throws IOException, ApiException {

        var request = new PaymentRefundRequest();
        request.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        request.setAmount(new Amount().currency(currency).value(value));
        request.setReference(UUID.randomUUID().toString());

        log.info("Refund request pspReference={} value={} {}", pspReference, value, currency);
        var response = modificationsApi.refundCapturedPayment(pspReference, request);
        log.info("*** Refund response pspReference={} status={} ***",
                response.getPspReference(), response.getStatus());
        return ResponseEntity.ok().body(response);
    }

    // Step 13 - Handle details call (triggered after the Native 3DS2 flow, called from the frontend in step 14)
    @PostMapping("/api/payments/details")
    public ResponseEntity<PaymentDetailsResponse> paymentsDetails(@RequestBody PaymentDetailsRequest detailsRequest) throws IOException, ApiException
    {
        log.info("PaymentDetailsRequest {}", detailsRequest);
        var response = paymentsApi.paymentsDetails(detailsRequest);
        log.info("PaymentDetailsResponse {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Step 14 - Handle Redirect 3DS2 during payment.
    @GetMapping("/handleShopperRedirect")
    public RedirectView redirect(@RequestParam(required = false) String payload, @RequestParam(required = false) String redirectResult) throws IOException, ApiException {
        var paymentDetailsRequest = new PaymentDetailsRequest();

        PaymentCompletionDetails paymentCompletionDetails = new PaymentCompletionDetails();

        // Handle redirect result or payload
        if (redirectResult != null && !redirectResult.isEmpty()) {
            // For redirect, you are redirected to an Adyen domain to complete the 3DS2 challenge
            // After completing the 3DS2 challenge, you get the redirect result from Adyen in the returnUrl
            // We then pass on the redirectResult
            paymentCompletionDetails.redirectResult(redirectResult);
        } else if (payload != null && !payload.isEmpty()) {
            paymentCompletionDetails.payload(payload);
        }

        paymentDetailsRequest.setDetails(paymentCompletionDetails);

        var paymentsDetailsResponse = paymentsApi.paymentsDetails(paymentDetailsRequest);
        log.info("PaymentsDetailsResponse {}", paymentsDetailsResponse);

        // Handle response and redirect user accordingly
        var redirectURL = "https://ominous-barnacle-454xrw54jgf7xwp-8080.app.github.dev/result/"; // Update your url here by replacing `http://localhost:8080` with where your application is hosted (if needed)
        switch (paymentsDetailsResponse.getResultCode()) {
            case AUTHORISED:
                redirectURL += "success";
                break;
            case PENDING:
            case RECEIVED:
                redirectURL += "pending";
                break;
            case REFUSED:
                redirectURL += "failed";
                break;
            default:
                redirectURL += "error";
                break;
        }
        return new RedirectView(redirectURL + "?reason=" + paymentsDetailsResponse.getResultCode());
    }
}
