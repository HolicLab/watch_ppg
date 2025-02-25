package com.samsung.health.hrtracker;

// RetryInterceptor.java
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;

public class RetryInterceptor implements Interceptor {
    private int maxRetries;
    private static final int MAX_ALLOWED_RETRIES = 3;  // 최대 재시도 횟수 제한
    public RetryInterceptor(int maxRetries) {
        if (maxRetries > MAX_ALLOWED_RETRIES) {
            throw new IllegalArgumentException("Max retries exceeds allowed limit");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException lastException = null;

        for (int retryCount = 0; retryCount < maxRetries; retryCount++) {
            try {
                response = chain.proceed(request);
                if (response.isSuccessful()) {
                    return response; // 성공하면 즉시 반환
                }
                // 실패하면 response를 닫고 계속 진행 (필수)
                if (response != null) {
                    response.close();
                }

            } catch (IOException e) {
                lastException = e;
            }
            // 재시도 전 잠시 대기 (exponential backoff 권장)
            try {
                Thread.sleep((long)Math.pow(2, retryCount) * 1000); // 2^retryCount * 1초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Retry interrupted", e);
            }
        }

        // 모든 재시도 실패
        if (response != null) {
            response.close();
        }

        if (lastException != null) {
            throw lastException;
        } else {
            throw new IOException("Request failed after " + maxRetries + " retries"); // 또는 적절한 예외
        }

    }
}
