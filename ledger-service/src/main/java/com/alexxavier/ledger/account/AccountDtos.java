package com.alexxavier.ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 200) String ownerName,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code") String currency) {
    }

    public record DepositRequest(@NotNull @Positive Long amountCents) {
    }

    public record AccountResponse(
            UUID id, String ownerName, String currency, long balanceCents, OffsetDateTime createdAt) {

        static AccountResponse of(Account account, long balanceCents) {
            return new AccountResponse(account.getId(), account.getOwnerName(), account.getCurrency(),
                    balanceCents, account.getCreatedAt());
        }
    }
}
