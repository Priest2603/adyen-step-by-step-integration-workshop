package com.adyen.workshop.controllers;

import com.adyen.model.RequestOptions;
import com.adyen.model.checkout.*;
import com.adyen.workshop.configurations.ApplicationConfiguration;
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

    public ApiController(ApplicationConfiguration applicationConfiguration, PaymentsApi paymentsApi) {
        this.applicationConfiguration = applicationConfiguration;
        this.paymentsApi = paymentsApi;
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

    // Shared shopper reference used for the tokenization demo. In a real app this would be a per-user identifier.
    private static final String SHOPPER_REFERENCE = "workshop-shopper-001";

    // Tokenization module - zero-auth /payments call that stores the payment method.
    // Amount is 0 USD, storePaymentMethod=true and recurringProcessingModel=Subscription
    // so Adyen returns a recurringDetailReference (token) via the RECURRING_CONTRACT / AUTHORISATION webhook.
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> payments(@RequestBody PaymentRequest body) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("USD")
                .value(0L);
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("https://ominous-barnacle-454xrw54jgf7xwp-8080.app.github.dev/handleShopperRedirect");

        // Tokenization flags - instruct Adyen to store the payment method and issue a token.
        paymentRequest.setStorePaymentMethod(true);
        paymentRequest.setShopperReference(SHOPPER_REFERENCE);
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        // 3DS2 Redirect support - keep in case the issuer still challenges a zero-auth.
        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("https://ominous-barnacle-454xrw54jgf7xwp-8080.app.github.dev");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("Zero-auth tokenization PaymentsRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("Zero-auth tokenization PaymentsResponse {}", response);

        if (response.getAdditionalData() != null) {
            var token = response.getAdditionalData().get("recurring.recurringDetailReference");
            if (token != null) {
                log.info("*** Token (recurringDetailReference) from zero-auth response: {} ***", token);
            }
        }

        return ResponseEntity.ok().body(response);
    }

    // Tokenization module - charge a stored token manually from the browser/curl.
    // Example: curl -X POST https://<host>/makepaymentwithtoken/8416567890123456
    @PostMapping("/makepaymentwithtoken/{token}")
    public ResponseEntity<PaymentResponse> makePaymentWithToken(@PathVariable String token) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("USD")
                .value(500L); // $5.00 subscription charge
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setReference(UUID.randomUUID().toString());

        // Build a card payment method that references the stored token.
        var cardDetails = new CardDetails();
        cardDetails.setType(CardDetails.TypeEnum.SCHEME);
        cardDetails.setStoredPaymentMethodId(token);
        paymentRequest.setPaymentMethod(new CheckoutPaymentMethod(cardDetails));

        // Subsequent merchant-initiated subscription charge - must match the model used when tokenizing.
        paymentRequest.setShopperReference(SHOPPER_REFERENCE);
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.CONTAUTH);
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("Token payment request for token={} {}", token, paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("Token payment response for token={} - pspReference={} resultCode={}",
                token, response.getPspReference(), response.getResultCode());
        log.info("Full token payment response: {}", response);

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
