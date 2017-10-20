/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBlockingQueueAsync;
import org.redisson.api.RFuture;
import org.redisson.api.RMap;
import org.redisson.api.RRemoteService;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandExecutor;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.remote.RRemoteServiceResponse;
import org.redisson.remote.RemoteServiceAck;
import org.redisson.remote.RemoteServiceCancelRequest;
import org.redisson.remote.RemoteServiceCancelResponse;
import org.redisson.remote.RemoteServiceKey;
import org.redisson.remote.RemoteServiceMethod;
import org.redisson.remote.RemoteServiceRequest;
import org.redisson.remote.RemoteServiceResponse;
import org.redisson.remote.ResponseEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.PlatformDependent;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonRemoteService extends BaseRemoteService implements RRemoteService {

    private static final Logger log = LoggerFactory.getLogger(RedissonRemoteService.class);

    private final Map<RemoteServiceKey, RemoteServiceMethod> beans = PlatformDependent.newConcurrentHashMap();
    private final Map<Class<?>, Set<RFuture<RemoteServiceRequest>>> futures = PlatformDependent.newConcurrentHashMap();

    public RedissonRemoteService(Codec codec, RedissonClient redisson, String name, CommandExecutor commandExecutor, String executorId, ConcurrentMap<String, ResponseEntry> responses) {
        super(codec, redisson, name, commandExecutor, executorId, responses);
    }
    
    @Override
    public <T> void register(Class<T> remoteInterface, T object) {
        register(remoteInterface, object, 1);
    }

    @Override
    public <T> void deregister(Class<T> remoteInterface) {
        for (Method method : remoteInterface.getMethods()) {
            RemoteServiceKey key = new RemoteServiceKey(remoteInterface, method.getName(), getMethodSignatures(method));
            beans.remove(key);
        }
        
        Set<RFuture<RemoteServiceRequest>> removedFutures = futures.remove(remoteInterface);
        if (removedFutures == null) {
            return;
        }
        
        for (RFuture<RemoteServiceRequest> future : removedFutures) {
            future.cancel(false);
        }
    }
    
    @Override
    public int getFreeWorkers(Class<?> remoteInterface) {
        Set<RFuture<RemoteServiceRequest>> futuresSet = futures.get(remoteInterface);
        return futuresSet.size();
    }
    
    @Override
    public <T> void register(Class<T> remoteInterface, T object, int workers) {
        register(remoteInterface, object, workers, commandExecutor.getConnectionManager().getExecutor());
    }

    @Override
    public <T> void register(Class<T> remoteInterface, T object, int workers, ExecutorService executor) {
        if (workers < 1) {
            throw new IllegalArgumentException("executorsAmount can't be lower than 1");
        }
        for (Method method : remoteInterface.getMethods()) {
            RemoteServiceMethod value = new RemoteServiceMethod(method, object);
            RemoteServiceKey key = new RemoteServiceKey(remoteInterface, method.getName(), getMethodSignatures(method));
            if (beans.put(key, value) != null) {
                return;
            }
        }

        Set<RFuture<RemoteServiceRequest>> values = Collections.newSetFromMap(PlatformDependent.<RFuture<RemoteServiceRequest>, Boolean>newConcurrentHashMap());
        futures.put(remoteInterface, values);
        
        String requestQueueName = getRequestQueueName(remoteInterface);
        RBlockingQueue<RemoteServiceRequest> requestQueue = redisson.getBlockingQueue(requestQueueName, codec);
        for (int i = 0; i < workers; i++) {
            subscribe(remoteInterface, requestQueue, executor);
        }
    }

    private <T> void subscribe(final Class<T> remoteInterface, final RBlockingQueue<RemoteServiceRequest> requestQueue,
            final ExecutorService executor) {
        Set<RFuture<RemoteServiceRequest>> futuresSet = futures.get(remoteInterface);
        if (futuresSet == null) {
            return;
        }
        final RFuture<RemoteServiceRequest> take = requestQueue.takeAsync();
        futuresSet.add(take);
        take.addListener(new FutureListener<RemoteServiceRequest>() {
            @Override
            public void operationComplete(Future<RemoteServiceRequest> future) throws Exception {
                Set<RFuture<RemoteServiceRequest>> futuresSet = futures.get(remoteInterface);
                if (futuresSet == null) {
                    return;
                }
                futuresSet.remove(take);
                
                if (!future.isSuccess()) {
                    if (future.cause() instanceof RedissonShutdownException) {
                        return;
                    }
                    log.error("Can't process the remote service request.", future.cause());
                    // re-subscribe after a failed takeAsync
                    subscribe(remoteInterface, requestQueue, executor);
                    return;
                }

                // do not subscribe now, see
                // https://github.com/mrniko/redisson/issues/493
                // subscribe(remoteInterface, requestQueue);

                final RemoteServiceRequest request = future.getNow();
                // check the ack only if expected
                if (request.getOptions().isAckExpected() && System.currentTimeMillis() - request.getDate() > request
                        .getOptions().getAckTimeoutInMillis()) {
                    log.debug("request: {} has been skipped due to ackTimeout");
                    // re-subscribe after a skipped ackTimeout
                    subscribe(remoteInterface, requestQueue, executor);
                    return;
                }

                final String responseName = getResponseQueueName(remoteInterface, request.getExecutorId());

                // send the ack only if expected
                if (request.getOptions().isAckExpected()) {
                    String ackName = getAckName(remoteInterface, request.getId());
                            RFuture<Boolean> ackClientsFuture = commandExecutor.evalWriteAsync(responseName,
                                    LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                                        "if redis.call('setnx', KEYS[1], 1) == 1 then " 
                                            + "redis.call('pexpire', KEYS[1], ARGV[2]);"
                                            + "redis.call('rpush', KEYS[2], ARGV[1]);"
//                                            + "redis.call('pexpire', KEYS[2], ARGV[2]);" 
                                            + "return 1;" 
                                        + "end;" 
                                        + "return 0;",
                                    Arrays.<Object>asList(ackName, responseName),
                                    encode(new RemoteServiceAck(request.getId())), request.getOptions().getAckTimeoutInMillis());

                            ackClientsFuture.addListener(new FutureListener<Boolean>() {
                                @Override
                                public void operationComplete(Future<Boolean> future) throws Exception {
                                    if (!future.isSuccess()) {
                                        if (future.cause() instanceof RedissonShutdownException) {
                                            return;
                                        }
                                        log.error("Can't send ack for request: " + request, future.cause());
                                        // re-subscribe after a failed send (ack)
                                        subscribe(remoteInterface, requestQueue, executor);
                                        return;
                                    }

                                    if (!future.getNow()) {
                                        subscribe(remoteInterface, requestQueue, executor);
                                        return;
                                    }

                                    executeMethod(remoteInterface, requestQueue, executor, request);
                                }
                            });
                } else {
                    executeMethod(remoteInterface, requestQueue, executor, request);
                }
            }

        });
    }
    
    private <T> void executeMethod(final Class<T> remoteInterface, final RBlockingQueue<RemoteServiceRequest> requestQueue,
            final ExecutorService executor, final RemoteServiceRequest request) {
        final RemoteServiceMethod method = beans.get(new RemoteServiceKey(remoteInterface, request.getMethodName(), request.getSignatures()));
        final String responseName = getResponseQueueName(remoteInterface, request.getExecutorId());
        

        final AtomicReference<RRemoteServiceResponse> responseHolder = new AtomicReference<RRemoteServiceResponse>();
        
        final RPromise<RemoteServiceCancelRequest> cancelRequestFuture = new RedissonPromise<RemoteServiceCancelRequest>();
        scheduleCheck(getCancelRequestMapName(remoteInterface), request.getId(), cancelRequestFuture);
        
        final java.util.concurrent.Future<?> submitFuture = executor.submit(new Runnable() {
            @Override
            public void run() {
                invokeMethod(remoteInterface, requestQueue, request, method, responseName, executor,
                        cancelRequestFuture, responseHolder);
            }
        });
        
        cancelRequestFuture.addListener(new FutureListener<RemoteServiceCancelRequest>() {
            @Override
            public void operationComplete(Future<RemoteServiceCancelRequest> future) throws Exception {
                if (!future.isSuccess()) {
                    return;
                }

                boolean res = submitFuture.cancel(future.getNow().isMayInterruptIfRunning());
                if (res) {
                    RemoteServiceCancelResponse response = new RemoteServiceCancelResponse(request.getId(), true);
                    if (!responseHolder.compareAndSet(null, response)) {
                        response = new RemoteServiceCancelResponse(request.getId(), false);
                    }
                    
                    // could be removed not from future object
                    if (future.getNow().isSendResponse()) {
                        String cancelResponseName = getCancelResponseMapName(remoteInterface);
                        RMap<String, RemoteServiceCancelResponse> map = redisson.getMap(cancelResponseName, codec);
                        map.putAsync(request.getId(), response);
                        map.expireAsync(60, TimeUnit.SECONDS);
                    }
                }
            }
        });
    }

    private <T> void invokeMethod(final Class<T> remoteInterface,
            final RBlockingQueue<RemoteServiceRequest> requestQueue, final RemoteServiceRequest request,
            RemoteServiceMethod method, String responseName, final ExecutorService executor,
            RFuture<RemoteServiceCancelRequest> cancelRequestFuture, final AtomicReference<RRemoteServiceResponse> responseHolder) {
        try {
            Object result = method.getMethod().invoke(method.getBean(), request.getArgs());

            RemoteServiceResponse response = new RemoteServiceResponse(request.getId(), result);
            responseHolder.compareAndSet(null, response);
        } catch (Exception e) {
            RemoteServiceResponse response = new RemoteServiceResponse(request.getId(), e.getCause());
            responseHolder.compareAndSet(null, response);
            log.error("Can't execute: " + request, e);
        }

        if (cancelRequestFuture != null) {
            cancelRequestFuture.cancel(false);
        }
        
        // send the response only if expected or task was canceled
        if (request.getOptions().isResultExpected()
                || responseHolder.get() instanceof RemoteServiceCancelResponse) {
//            long timeout = 60 * 1000;
//            if (request.getOptions().getExecutionTimeoutInMillis() != null) {
//                timeout = request.getOptions().getExecutionTimeoutInMillis();
//            }

            RBlockingQueueAsync<RRemoteServiceResponse> queue = redisson.getBlockingQueue(responseName, codec);
            RFuture<Void> clientsFuture = queue.putAsync(responseHolder.get());

            clientsFuture.addListener(new FutureListener<Void>() {
                @Override
                public void operationComplete(Future<Void> future) throws Exception {
                    // interface has been deregistered 
                    if (futures.get(remoteInterface) == null) {
                        return;
                    }
                    
                    if (!future.isSuccess()) {
                        if (future.cause() instanceof RedissonShutdownException) {
                            return;
                        }
                        log.error("Can't send response: " + responseHolder.get() + " for request: " + request,
                                future.cause());
                    }
                    
                    // re-subscribe anyways (fail or success) after the send
                    // (response)
                    subscribe(remoteInterface, requestQueue, executor);
                }
            });
        } else {
            // re-subscribe anyways after the method invocation
            subscribe(remoteInterface, requestQueue, executor);
        }
    }

}
