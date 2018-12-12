package network.genaro.storage;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Callback;
import okhttp3.Call;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static javax.crypto.Cipher.DECRYPT_MODE;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static network.genaro.storage.Parameters.*;

import static network.genaro.storage.Genaro.GenaroStrError;

public class Downloader implements Runnable {
    private static final Logger logger = LogManager.getLogger(Genaro.class);

    private String path;
    private String tempPath;
    private Genaro bridge;
    private String bucketId;
    private String fileId;

    private AtomicLong downloadedBytes = new AtomicLong();
    private long totalBytes;

    // increased uploaded bytes since last onProgress Call
    private AtomicLong deltaDownloaded = new AtomicLong();

    private long shardSize;

    private FileChannel downFileChannel;

    private CompletableFuture<File> futureGetFileInfo;
    private CompletableFuture<List<Pointer>> futureGetPointers;
    private CompletableFuture<Void> futureAllRequestShard;

    private boolean isCanceled = false;
    private boolean isStopping = false;

    private DownloadCallback downloadCallback;

    // 使用CachedThreadPool比较耗内存，并发高的时候会造成内存溢出
    // private static final ExecutorService uploaderExecutor = Executors.newCachedThreadPool();

    // 如果是CPU密集型应用，则线程池大小建议设置为N+1，如果是IO密集型应用，则线程池大小建议设置为2N+1，下载和上传都是IO密集型。（parallelStream也能实现多线程，但是适用于CPU密集型应用）
    private final ExecutorService downloaderExecutor = Executors.newFixedThreadPool(2 * Runtime.getRuntime().availableProcessors() + 1);

    private final OkHttpClient downHttpClient = new OkHttpClient.Builder()
            .connectTimeout(GENARO_OKHTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(GENARO_OKHTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(GENARO_OKHTTP_READ_TIMEOUT, TimeUnit.SECONDS)
            .build();

    public Downloader(final Genaro bridge, final String bucketId, final String fileId, final String path, final DownloadCallback downloadCallback) {
        this.bridge = bridge;
        this.fileId = fileId;
        this.bucketId = bucketId;
        this.path = path;
        this.tempPath = path + ".genarotemp";
        this.downloadCallback = downloadCallback;
    }

    public Downloader(final Genaro bridge, final String bucketId, final String fileId, final String path) {
        this(bridge, bucketId, fileId, path, new DownloadCallback() {});
    }

    public CompletableFuture<File> getFutureGetFileInfo() {
        return futureGetFileInfo;
    }

    public void setFutureGetFileInfo(CompletableFuture<File> futureGetFileInfo) {
        this.futureGetFileInfo = futureGetFileInfo;
    }

    public CompletableFuture<List<Pointer>> getFutureGetPointers() {
        return futureGetPointers;
    }

    public void setFutureGetPointers(CompletableFuture<List<Pointer>> futureGetPointers) {
        this.futureGetPointers = futureGetPointers;
    }

    public OkHttpClient getDownHttpClient() {
        return downHttpClient;
    }

    class RequestShardCallbackFuture extends CompletableFuture<Response> implements Callback {

        public RequestShardCallbackFuture(Pointer pointer) {
            this.pointer = pointer;
        }

        private Pointer pointer;
        private static final int SEGMENT_SIZE = 2 * 1024;

        public void fail() {
            logger.error(String.format("Download Pointer %d failed", pointer.getIndex()));
            downloadedBytes.addAndGet(-pointer.getDownloadedSize());
            pointer.setDownloadedSize(0);
        }

        @Override
        public void onResponse(Call call, Response response) {
            int code = response.code();

            int errorStatus = 0;
            if (code == 401 || code == 403) {
                errorStatus = GENARO_FARMER_AUTH_ERROR;
            } else if (code == 504) {
                errorStatus = GENARO_FARMER_TIMEOUT_ERROR;
            } else if(code != 200) {
                errorStatus = GENARO_FARMER_REQUEST_ERROR;
            }

            if(errorStatus != 0) {
                fail();
                super.completeExceptionally(new GenaroRuntimeException(GenaroStrError(GENARO_FILE_WRITE_ERROR)));
                return;
            }

            byte[] buff = new byte[SEGMENT_SIZE];
            int delta;

            try (InputStream is = response.body().byteStream();
                 BufferedInputStream bis = new BufferedInputStream(is)) {
                while ((delta = bis.read(buff)) != -1) {
                    downFileChannel.write(ByteBuffer.wrap(buff, 0, delta), shardSize * pointer.getIndex() + pointer.getDownloadedSize());
                    pointer.setDownloadedSize(pointer.getDownloadedSize() + delta);

                    downloadedBytes.addAndGet(delta);
                    deltaDownloaded.addAndGet(delta);

                    if (deltaDownloaded.floatValue() / totalBytes >= 0.001) {  // call onProgress every 0.1%
                       downloadCallback.onProgress(downloadedBytes.floatValue() / totalBytes);
                       deltaDownloaded.set(0);
                   }
                }

                // if downloaded size is not the same with total size
                if(pointer.getDownloadedSize() != pointer.getSize()) {
                    fail();
                    super.completeExceptionally(new GenaroRuntimeException(GenaroStrError(GENARO_FARMER_INTEGRITY_ERROR)));
                    return;
                }

                logger.info(String.format("Download Pointer %d finished", pointer.getIndex()));
            } catch (IOException e) {
                // BasicUtil.cancelOkHttpCallWithTag(okHttpClient, "requestShard") will cause an SocketException
                if (e instanceof SocketException) {
                    super.completeExceptionally(new GenaroRuntimeException(GenaroStrError(GENARO_TRANSFER_CANCELED)));
                } else {
                    super.completeExceptionally(new GenaroRuntimeException(GenaroStrError(GENARO_FARMER_REQUEST_ERROR)));
                }
                return;
            }

            super.complete(response);
        }

        @Override
        public void onFailure(Call call, IOException e) {
            super.completeExceptionally(e);
        }
    }

    // Void not void, because it will be used as a supplier of Completable.supplyAsync
    private Void requestShard(final Pointer pointer) {
        Farmer farmer = pointer.getFarmer();
        String url = String.format("http://%s:%s/shards/%s?token=%s", farmer.getAddress(), farmer.getPort(), pointer.getHash(), pointer.getToken());
        Request request = new Request.Builder()
                                     .tag("requestShard")
                                     .header("x-storj-node-id", farmer.getNodeID())
                                     .url(url)
                                     .get()
                                     .build();

        logger.info(String.format("Starting download Pointer %d...", pointer.getIndex()));

        RequestShardCallbackFuture future = new RequestShardCallbackFuture(pointer);
        downHttpClient.newCall(request).enqueue(future);

        try {
            future.get();
        } catch (Exception e) {
            if(e instanceof ExecutionException) {
                Throwable cause = e.getCause();
                if (cause instanceof SocketTimeoutException) {
                    throw new GenaroRuntimeException(GenaroStrError(GENARO_FARMER_TIMEOUT_ERROR));
                } else if (cause instanceof GenaroRuntimeException) {
                    throw new GenaroRuntimeException(cause.getMessage());
                }
            } else {
                throw new GenaroRuntimeException(GenaroStrError(GENARO_FARMER_REQUEST_ERROR));
            }
        }
        return null;
    }

    public void start() {
        downloadCallback.onBegin();

        // request info
        File file;
        try {
            file = bridge.getFileInfo(this, bucketId, fileId);
        } catch (Exception e) {
            stop();
            if(e instanceof CancellationException) {
                downloadCallback.onCancel();
            } else if(e instanceof TimeoutException) {
                downloadCallback.onFail(GenaroStrError(GENARO_BRIDGE_TIMEOUT_ERROR));
            } else if(e instanceof ExecutionException && e.getCause() instanceof GenaroRuntimeException) {
                downloadCallback.onFail(e.getCause().getMessage());
            } else {
                downloadCallback.onFail(GenaroStrError(GENARO_BRIDGE_REQUEST_ERROR));
            }
            return;
        }

        // check if cancel() is called
        if(isCanceled) {
            downloadCallback.onCancel();
            return;
        }

        // request pointers
        List<Pointer> pointers;
        try {
            pointers = bridge.getPointers(this, bucketId, fileId);
        } catch (Exception e) {
            stop();
            if(e instanceof CancellationException) {
                downloadCallback.onCancel();
            } else if(e instanceof TimeoutException) {
                downloadCallback.onFail(GenaroStrError(GENARO_BRIDGE_TIMEOUT_ERROR));
            } else if(e instanceof ExecutionException && e.getCause() instanceof GenaroRuntimeException) {
                downloadCallback.onFail(e.getCause().getMessage());
            } else {
                downloadCallback.onFail(GenaroStrError(GENARO_BRIDGE_REQUEST_ERROR));
            }
            return;
        }

        // check if cancel() is called
        if(isCanceled) {
            downloadCallback.onCancel();
            return;
        }

        boolean hasMissingShard = pointers.stream().anyMatch(pointer -> pointer.isMissing());

        // TODO: isRs() is not the
//        boolean canRecoverShards = file.isRs();
        boolean canRecoverShards = false;

        if(file.isRs()) {
            int missingPointers = (int) pointers.stream().filter(pointer -> pointer.isMissing()).count();

            // TODO:
//            if(missingPointers <= total_parity_pointers) {
//                canRecoverShards = true;
//            }
        }

        if(pointers.size() == 0 || (hasMissingShard && !canRecoverShards)) {
            stop();
            downloadCallback.onFail(GenaroStrError(GENARO_FILE_SHARD_MISSING_ERROR));
            return;
        }

        if(hasMissingShard) {
            //TODO: queue_recover_shards
        }

        // set shard size to the size of the first shard
        shardSize = pointers.get(0).getSize();

        long fileSize = 0;
        for (Pointer pointer: pointers) {
            logger.info(pointer.toBriefString());
            long size = pointer.getSize();
            totalBytes += size;
            if(!pointer.isParity()) {
                fileSize += size;
            }
        }

        // TODO: check for replace pointer

        try {
            downFileChannel = FileChannel.open(Paths.get(tempPath), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException e) {
            stop();
            downloadCallback.onFail("Create temp file error");
            return;
        }

        // downloading
        CompletableFuture<Void>[] downFutures = pointers
            .stream()
            // TODO: not only download none parity shards
//            .filter(pointer -> !pointer.isParity())
            .map(pointer -> CompletableFuture.supplyAsync(() -> requestShard(pointer), downloaderExecutor))
            .toArray(CompletableFuture[]::new);

        futureAllRequestShard = CompletableFuture.allOf(downFutures);

        try {
            futureAllRequestShard.get();
        } catch (Exception e) {
            stop();
            if(e instanceof CancellationException) {
                downloadCallback.onCancel();
            } else if(e instanceof ExecutionException && e.getCause() instanceof GenaroRuntimeException) {
                downloadCallback.onFail(e.getCause().getMessage());
            } else {
                downloadCallback.onFail(GenaroStrError(GENARO_FARMER_REQUEST_ERROR));
            }
            return;
        }

        // check if cancel() is called
        if(isCanceled) {
            downloadCallback.onCancel();
            return;
        }

        if(downloadedBytes.get() != totalBytes) {
            logger.warn("Downloaded bytes is not the same with total bytes, downloaded bytes: " + downloadedBytes + ", totalBytes: " + totalBytes);
        }

        try {
            downFileChannel.truncate(fileSize);
        } catch (IOException e) {
            stop();
            downloadCallback.onFail(GenaroStrError(GENARO_FILE_RESIZE_ERROR));
            return;
        }

        // decryption:
        logger.info("Download complete, begin to decrypt...");
        byte[] bucketId = Hex.decode(file.getBucket());
        byte[] index   = Hex.decode(file.getIndex());

        byte[] fileKey;
        try {
            fileKey = CryptoUtil.generateFileKey(bridge.getPrivateKey(), bucketId, index);
        } catch (Exception e) {
            stop();
            downloadCallback.onFail("Generate file key error");
            return;
        }

        byte[] ivBytes = Arrays.copyOf(index, 16);
        SecretKeySpec keySpec = new SecretKeySpec(fileKey, "AES");
        IvParameterSpec iv = new IvParameterSpec(ivBytes);

        javax.crypto.Cipher cipher;
        try {
            cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(DECRYPT_MODE, keySpec, iv);
        } catch (Exception e) {
            stop();
            downloadCallback.onFail("Init decryption context error");
            return;
        }

        // check if cancel() is called
        if(isCanceled) {
            downloadCallback.onCancel();
            return;
        }

        try (InputStream in = Channels.newInputStream(downFileChannel);
             InputStream cypherIn = new CipherInputStream(in, cipher)) {
            try {
                Files.copy(cypherIn, Paths.get(path));
            } catch (IOException e) {
                stop();
                downloadCallback.onFail("Create file error");
                return;
            }
        } catch (IOException e) {
            stop();
            downloadCallback.onFail(GenaroStrError(GENARO_FILE_DECRYPTION_ERROR));
            return;
        }

        try {
            downFileChannel.close();
        } catch (Exception e) {
            // do not call downloadCallback.onFail here
            logger.warn("File close exception");
        }

        logger.info("Decrypt complete, download is success");

        // download success
        downloadCallback.onFinish();
    }

    public void stop() {
        if (isStopping) {
            return;
        }

        isStopping = true;

        // cancel getFileInfo
        if(futureGetFileInfo != null && !futureGetFileInfo.isDone()) {
            // cancel the okhttp3 transfer
            BasicUtil.cancelOkHttpCallWithTag(downHttpClient, "getFileInfo");
            // will cause a CancellationException, and will be caught on bridge.getFileInfo
            futureGetFileInfo.cancel(true);
        }

        // cancel getPointers
        if(futureGetPointers != null && !futureGetPointers.isDone()) {
            // cancel the okhttp3 transfer
            BasicUtil.cancelOkHttpCallWithTag(downHttpClient, "getPointersRaw");
            // will cause a CancellationException, and will be caught on bridge.getPointers
            futureGetPointers.cancel(true);
        }

        // cancel requestShard
        if(futureAllRequestShard != null && !futureAllRequestShard.isDone()) {
            // cancel the okhttp3 transfer
            BasicUtil.cancelOkHttpCallWithTag(downHttpClient, "requestShard");
            // will cause a CancellationException, and will be caught on futureAllRequestShard.get
            futureAllRequestShard.cancel(true);
        }

        downloaderExecutor.shutdown();
    }

    // Non-blocking
    public void cancel() {
        isCanceled = true;
        stop();
    }

    @Override
    public void run() {
        start();
    }
}
