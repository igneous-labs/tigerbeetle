package com.tigerbeetle;

import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class Client implements AutoCloseable {
    static {
        JNILoader.loadFromJar();
    }

    private static final int DEFAULT_MAX_CONCURRENCY = 32;

    private final int clusterID;
    private final int maxConcurrency;
    private final Semaphore maxConcurrencySemaphore;
    private long clientHandle;
    private long packetsHead;
    private long packetsTail;

    /**
     * Initializes an instance of TigerBeetle client. This class is thread-safe and for optimal
     * performance, a single instance should be shared between multiple concurrent tasks.
     * <p>
     * Multiple clients can be instantiated in case of connecting to more than one TigerBeetle
     * cluster.
     *
     * @param clusterID
     * @param replicaAddresses
     * @param maxConcurrency
     *
     * @throws InitializationException if an error occurred initializing this client. See
     *         {@link InitializationException.Status} for more details.
     *
     * @throws IllegalArgumentException if {@code clusterID} is negative.
     * @throws IllegalArgumentException if {@code replicaAddresses} is empty or presented in
     *         incorrect format.
     * @throws NullPointerException if {@code replicaAddresses} is null or any element in the array
     *         is null.
     * @throws IllegalArgumentException if {@code maxConcurrency} is zero or negative.
     */
    public Client(int clusterID, String[] replicaAddresses, int maxConcurrency) {
        this(clusterID, maxConcurrency);

        if (replicaAddresses == null)
            throw new NullPointerException("Replica addresses cannot be null");

        if (replicaAddresses.length == 0)
            throw new IllegalArgumentException("Empty replica addresses");

        var joiner = new StringJoiner(",");
        for (var address : replicaAddresses) {
            if (address == null)
                throw new NullPointerException("Replica address cannot be null");
            joiner.add(address);
        }

        int status = clientInit(clusterID, joiner.toString(), maxConcurrency);

        if (status == InitializationException.Status.INVALID_ADDRESS)
            throw new IllegalArgumentException("Replica addresses format is invalid.");

        if (status != 0)
            throw new InitializationException(status);
    }


    /**
     * Initializes an instance of TigerBeetle client. This class is thread-safe and for optimal
     * performance, a single instance should be shared between multiple concurrent tasks.
     * <p>
     * Multiple clients can be instantiated in case of connecting to more than one TigerBeetle
     * cluster.
     *
     * @param clusterID
     * @param replicaAddresses
     *
     * @throws InitializationException if an error occurred initializing this client. See
     *         {@link InitializationException.Status} for more details.
     *
     * @throws IllegalArgumentException if {@code clusterID} is negative.
     * @throws IllegalArgumentException if {@code replicaAddresses} is empty or presented in
     *         incorrect format.
     * @throws NullPointerException if {@code replicaAddresses} is null or any element in the array
     *         is null.
     */
    public Client(int clusterID, String[] replicaAddresses) {
        this(clusterID, replicaAddresses, DEFAULT_MAX_CONCURRENCY);
    }

    Client(int clusterID, int maxConcurrency) {
        if (clusterID < 0)
            throw new IllegalArgumentException("ClusterID must be positive");

        // Cap the maximum amount of packets
        if (maxConcurrency <= 0)
            throw new IllegalArgumentException("Invalid maxConcurrency");

        if (maxConcurrency > 4096) {
            maxConcurrency = 4096;
        }

        this.clusterID = clusterID;
        this.maxConcurrency = maxConcurrency;
        this.maxConcurrencySemaphore = new Semaphore(maxConcurrency, false);
    }

    /**
     * Submits a new account to be created.
     *
     * @param account a single {@link com.tigerbeetle.Account} instance to be created.
     * @return a {@link com.tigerbeetle.CreateAccountResult}.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws NullPointerException if {@code account} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CreateAccountResult createAccount(Account account) throws RequestException {
        var batch = new AccountsBatch(1);
        batch.add(account);

        CreateAccountsResult[] results = createAccounts(batch);
        if (results.length == 0) {
            return CreateAccountResult.Ok;
        } else {
            return results[0].result;
        }
    }

    /**
     * Submits a batch of new accounts to be created.
     *
     * @param batch an array containing all accounts to be created.
     * @return an empty array on success, or an array of
     *         {@link com.tigerbeetle.CreateAccountsResult} describing the reason.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CreateAccountsResult[] createAccounts(Account[] batch) throws RequestException {
        return createAccounts(new AccountsBatch(batch));
    }

    /**
     * Submits a batch of new accounts to be created.
     *
     * @param batch a {@link com.tigerbeetle.AccountsBatch} instance containing all accounts to be
     *        created.
     * @return an empty array on success, or an array of
     *         {@link com.tigerbeetle.CreateAccountsResult} describing the reason.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CreateAccountsResult[] createAccounts(AccountsBatch batch) throws RequestException {
        var request = BlockingRequest.createAccounts(this, batch);
        request.beginRequest();
        return request.waitForResult();
    }

    /**
     * Submits a batch of new accounts to be created asynchronously.
     *
     * @see Client#createAccounts(Account[])
     * @param batch an array containing all accounts to be created.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<CreateAccountsResult[]> createAccountsAsync(Account[] batch) {
        return createAccountsAsync(new AccountsBatch(batch));
    }

    /**
     * Submits a batch of new accounts to be created asynchronously.
     *
     * @see Client#createAccounts(AccountsBatch)
     * @param batch a {@link com.tigerbeetle.AccountsBatch} instance containing all accounts to be
     *        created.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<CreateAccountsResult[]> createAccountsAsync(AccountsBatch batch) {
        var request = AsyncRequest.createAccounts(this, batch);
        request.beginRequest();
        return request.getFuture();
    }

    /**
     * Looks up a single account.
     *
     * @param uuid the account's identifier.
     * @return a {@link com.tigerbeetle.Account} or null if not found.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws NullPointerException if {@code uuid} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public Account lookupAccount(UUID uuid) throws RequestException {
        var batch = new UUIDsBatch(1);
        batch.add(uuid);

        Account[] results = lookupAccounts(batch);
        if (results.length == 0) {
            return null;
        } else {
            return results[0];
        }
    }

    /**
     * Looks up a batch of accounts.
     *
     * @param batch an array containing all accounts ids.
     * @return an array of {@link com.tigerbeetle.Account} containing all accounts found.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public Account[] lookupAccounts(UUID[] batch) throws RequestException {
        return lookupAccounts(new UUIDsBatch(batch));
    }

    /**
     * Looks up a batch of accounts.
     *
     * @param batch an {@link com.tigerbeetle.UUIDsBatch} containing all account ids.
     * @return an array of {@link com.tigerbeetle.Account} containing all accounts found.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public Account[] lookupAccounts(UUIDsBatch batch) throws RequestException {
        var request = BlockingRequest.lookupAccounts(this, batch);
        request.beginRequest();
        return request.waitForResult();
    }

    /**
     * Looks up a batch of accounts asynchronously.
     *
     * @see Client#lookupAccounts(UUID[])
     * @param batch an array containing all account ids.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<Account[]> lookupAccountsAsync(UUID[] batch) {
        return lookupAccountsAsync(new UUIDsBatch(batch));
    }

    /**
     * Looks up a batch of accounts asynchronously.
     *
     * @see Client#lookupAccounts(UUID[])
     * @param batch an {@link com.tigerbeetle.UUIDsBatch} containing all account ids.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<Account[]> lookupAccountsAsync(UUIDsBatch batch) {
        var request = AsyncRequest.lookupAccounts(this, batch);
        request.beginRequest();
        return request.getFuture();
    }

    /**
     * Submits a new transfer to be created.
     *
     * @param transfer a single {@link com.tigerbeetle.Transfer} instance to be created.
     * @return a {@link com.tigerbeetle.CreateTransferResult}
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws NullPointerException if {@code transfer} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CreateTransferResult createTransfer(Transfer transfer) throws RequestException {
        var batch = new TransfersBatch(1);
        batch.add(transfer);

        CreateTransfersResult[] results = createTransfers(batch);
        if (results.length == 0) {
            return CreateTransferResult.Ok;
        } else {
            return results[0].result;
        }
    }

    /**
     * Submits a batch of new transfers to be created.
     *
     * @param batch an array containing all transfers to be created.
     * @return an empty array on success, or an array of
     *         {@link com.tigerbeetle.CreateTransfersResult} describing the reason.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CreateTransfersResult[] createTransfers(Transfer[] batch) throws RequestException {
        return createTransfers(new TransfersBatch(batch));
    }

    /**
     * Submits a batch of new transfers to be created.
     *
     * @param batch a {@link com.tigerbeetle.TransfersBatch} instance containing all transfers to be
     *        created.
     * @return an empty array on success, or an array of
     *         {@link com.tigerbeetle.CreateTransfersResult} describing the reason.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CreateTransfersResult[] createTransfers(TransfersBatch batch) throws RequestException {
        var request = BlockingRequest.createTransfers(this, batch);
        request.beginRequest();
        return request.waitForResult();
    }

    /**
     * Submits a batch of new transfers to be created asynchronously.
     *
     * @param batch an array containing all transfers to be created.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<CreateTransfersResult[]> createTransfersAsync(Transfer[] batch) {
        return createTransfersAsync(new TransfersBatch(batch));
    }

    /**
     * Submits a batch of new transfers to be created asynchronously.
     *
     * @param batch a {@link com.tigerbeetle.TransfersBatch} instance containing all transfers to be
     *        created.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<CreateTransfersResult[]> createTransfersAsync(TransfersBatch batch) {
        var request = AsyncRequest.createTransfers(this, batch);
        request.beginRequest();
        return request.getFuture();
    }

    /**
     * Looks up a single transfer.
     *
     * @param uuid the transfer's identifier.
     * @return a {@link com.tigerbeetle.Transfer} or null if not found.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws NullPointerException if {@code uuid} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public Transfer lookupTransfer(UUID uuid) throws RequestException {
        var batch = new UUIDsBatch(1);
        batch.add(uuid);

        Transfer[] results = lookupTransfers(batch);
        if (results.length == 0) {
            return null;
        } else {
            return results[0];
        }
    }

    /**
     * Looks up a batch of transfers.
     *
     * @param batch an array containing all transfer ids.
     * @return an array of {@link com.tigerbeetle.Transfer} containing all transfers found.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public Transfer[] lookupTransfers(UUID[] batch) throws RequestException {
        return lookupTransfers(new UUIDsBatch(batch));
    }

    /**
     * Looks up a batch of transfers.
     *
     * @param batch an {@link com.tigerbeetle.UUIDsBatch} containing all transfer ids.
     * @return an array of {@link com.tigerbeetle.Transfer} containing all transfers found.
     * @throws RequestException refer to {@link com.tigerbeetle.RequestException.Status} for more
     *         details.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public Transfer[] lookupTransfers(UUIDsBatch batch) throws RequestException {
        var request = BlockingRequest.lookupTransfers(this, batch);
        request.beginRequest();
        return request.waitForResult();
    }

    /**
     * Looks up a batch of transfers asynchronously.
     *
     * @see Client#lookupTransfers(UUID[])
     * @param batch an array containing all transfer ids.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null or any element in the array is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<Transfer[]> lookupTransfersAsync(UUID[] batch) {
        return lookupTransfersAsync(new UUIDsBatch(batch));
    }

    /**
     * Looks up a batch of transfers asynchronously.
     *
     * @see Client#lookupTransfers(UUIDsBatch)
     * @param batch an {@link com.tigerbeetle.UUIDsBatch} containing all transfer ids.
     * @return a {@link java.util.concurrent.CompletableFuture} to be completed.
     * @throws IllegalArgumentException if {@code batch} is empty.
     * @throws NullPointerException if {@code batch} is null.
     * @throws IllegalStateException if this client is closed.
     */
    public CompletableFuture<Transfer[]> lookupTransfersAsync(UUIDsBatch batch) {
        var request = AsyncRequest.lookupTransfers(this, batch);
        request.beginRequest();
        return request.getFuture();
    }

    void submit(Request<?> request) {
        long packet = adquirePacket();
        submit(clientHandle, request, packet);
    }

    private long adquirePacket() {

        // Assure that only the max number of concurrent requests can adquire a packet
        // It forces other threads to wait until a packet became available
        // We also assure that the clientHandle will be zeroed only after all permits
        // have been released
        final int TIMEOUT = 5;
        boolean adquired = false;
        do {

            if (clientHandle == 0)
                throw new IllegalStateException("Client is closed");

            try {
                adquired = maxConcurrencySemaphore.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedException) {

                // This exception should never exposed by the API to be handled by the user
                throw new AssertionError(interruptedException,
                        "Unexpected thread interruption on adquiring a packet.");
            }

        } while (!adquired);

        synchronized (this) {
            return popPacket(packetsHead, packetsTail);
        }
    }

    void returnPacket(long packet) {
        synchronized (this) {
            // Check if the client is closing
            if (clientHandle != 0) {
                pushPacket(packetsHead, packetsTail, packet);
            }
        }

        // Releasing the packet to be used by another thread
        maxConcurrencySemaphore.release();
    }

    /*
     * Closes the client, freeing all resources. <p> This method causes the current thread to wait
     * for all ongoing requests to finish.
     *
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public void close() throws Exception {

        if (clientHandle != 0) {

            // Acquire all permits, forcing to wait for any processing thread to release
            this.maxConcurrencySemaphore.acquireUninterruptibly(maxConcurrency);

            // Deinit and sinalize that this client is closed by setting the handles to 0
            synchronized (this) {
                clientDeinit(clientHandle);

                clientHandle = 0;
                packetsHead = 0;
                packetsTail = 0;
            }
        }
    }

    private native void submit(long clientHandle, Request<?> request, long packet);

    private native int clientInit(int clusterID, String addresses, int maxConcurrency);

    private native void clientDeinit(long clientHandle);

    private native long popPacket(long packetHead, long packetTail);

    private native void pushPacket(long packetHead, long packetTail, long packet);
}
