package com.alexxavier.ledger.transfer;

import com.alexxavier.ledger.common.BadRequestException;
import com.alexxavier.ledger.common.SystemAccounts;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Entry point for moving money. Validates the request, runs the transactional {@link TransferExecutor}, and
 * resolves the race where two requests with the same brand-new Idempotency-Key arrive at the same moment.
 */
@Service
public class TransferService {

    static final int MAX_KEY_LENGTH = 255;

    private final TransferExecutor executor;

    public TransferService(TransferExecutor executor) {
        this.executor = executor;
    }

    /** A customer transfer. Neither side may be a system account; the source may not overdraw. */
    public TransferResult transfer(String idempotencyKey, TransferRequest request) {
        validateKey(idempotencyKey);
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new BadRequestException("SAME_ACCOUNT", "fromAccountId and toAccountId must differ");
        }
        if (SystemAccounts.isSystem(request.fromAccountId()) || SystemAccounts.isSystem(request.toAccountId())) {
            throw new BadRequestException("SYSTEM_ACCOUNT", "System accounts cannot be used in transfers");
        }
        return run(idempotencyKey, request.fingerprint("transfer"), request, false);
    }

    /** Test-only deposit: a transfer from the EXTERNAL system account, which may go negative. */
    public TransferResult deposit(String idempotencyKey, UUID accountId, long amountCents) {
        validateKey(idempotencyKey);
        if (SystemAccounts.isSystem(accountId)) {
            throw new BadRequestException("SYSTEM_ACCOUNT", "Cannot deposit into a system account");
        }
        var request = new TransferRequest(SystemAccounts.EXTERNAL, accountId, amountCents);
        // namespace deposit keys so they can never collide with a client's transfer keys
        return run("deposit:" + idempotencyKey, request.fingerprint("deposit"), request, true);
    }

    private TransferResult run(String key, String hash, TransferRequest request, boolean sourceMayGoNegative) {
        try {
            return executor.execute(key, hash, request, sourceMayGoNegative);
        } catch (DataIntegrityViolationException | TransferExecutor.LostIdempotencyRaceException e) {
            // Two requests with the same new key both passed the "seen it?" check. The first to commit won;
            // our transaction was rolled back (no money moved). Answer with the winner's stored response.
            TransferResult winner = executor.replayStored(key, hash);
            if (winner == null) {
                throw e;   // not an idempotency race: a genuine integrity problem, surface it
            }
            return winner;
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
    }
}
