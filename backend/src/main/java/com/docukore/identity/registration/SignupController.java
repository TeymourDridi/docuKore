package com.docukore.identity.registration;

import com.docukore.identity.CurrentAccount;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class SignupController
{
    /** Signup service for registration workflow. */
    private final SignupService signup;
    /** Current account accessor. */
    private final CurrentAccount currentAccount;

    public SignupController(SignupService signup, CurrentAccount currentAccount)
    {
        this.signup = signup;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/signup")
    ResponseEntity<SignupResponse> register(@RequestBody SignupRequest request)
    {
        return response(signup.register(request));
    }

    @PostMapping("/verify-email")
    ResponseEntity<SignupResponse> verify(@RequestBody VerificationRequest request)
    {
        return response(signup.verifyEmail(request.token()));
    }

    @PostMapping("/resend-verification")
    ResponseEntity<AcceptedResponse> resend(@RequestBody ResendRequest request)
    {
        signup.resendVerification(request.email());
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(new AcceptedResponse("ACCEPTED"));
    }

    @PostMapping("/registration/retry")
    ResponseEntity<SignupResponse> retry(@RequestBody RetryRequest request)
    {
        return response(signup.retry(request.registrationId(), currentAccount.requireId()));
    }

    private static ResponseEntity<SignupResponse> response(SignupResponse response)
    {
        HttpStatus status = response.status() == SignupResponse.Status.ACTIVE
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store").body(response);
    }

    public record VerificationRequest(String token)
    {
        @Override
        public String toString()
        {
            return "VerificationRequest[redacted]";
        }
    }

    public record ResendRequest(String email)
    {
        @Override
        public String toString()
        {
            return "ResendRequest[redacted]";
        }
    }

    public record RetryRequest(UUID registrationId)
    {
    }

    public record AcceptedResponse(String status)
    {
    }
}
