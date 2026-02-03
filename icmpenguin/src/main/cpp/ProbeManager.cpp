/*
 * Copyright (c) 2025 Alexander Yaburov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include "ProbeManager.h"

#include <utility>
#include <random>
#include <arpa/inet.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <linux/ip.h>
#include <linux/icmp.h>
#include <linux/icmpv6.h>
#include <linux/errqueue.h>
#include <android/log_macros.h>
#include <unistd.h>
#include <jni.h>
#include "jni_methods.h"

ProbeManager::ProbeManager(const char *remote_ip, const char *source_ip, void *callback_obj,
                           JNICallback trigger_callback) {
    this->remote_ip = std::string(remote_ip);
    if (try_init_addr(AF_INET, remote_ip, remote_addr) <= 0) {
        if (try_init_addr(AF_INET6, remote_ip, remote_addr) <= 0) {
            ALOGE("Invalid network address format");
            return;
        }
    }
    this->source_ip = std::string(source_ip);
    if (!this->source_ip.empty()) {
        if (try_init_addr(AF_INET, source_ip, source_addr) <= 0) {
            if (try_init_addr(AF_INET6, source_ip, source_addr) <= 0) {
                ALOGE("Invalid source address format");
                // Fallback to default
                this->source_ip = "";
            }
        }
    }
    this->callback_obj = callback_obj;
    this->trigger_callback = std::move(trigger_callback);
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 0xffff);
    ident = dis(gen);
}

int ProbeManager::try_init_addr(int family, const char *addr, sockaddr_storage &addr_storage) {
    memset(&addr_storage, 0, sizeof(addr_storage));
    void *sin_addr_ptr = nullptr;
    if (family == AF_INET) {
        auto *sa_in = reinterpret_cast<struct sockaddr_in *>(&addr_storage);
        sa_in->sin_family = AF_INET; // Set family
        sin_addr_ptr = &(sa_in->sin_addr);
    } else {
        auto *sa_in6 = reinterpret_cast<struct sockaddr_in6 *>(&addr_storage);
        sa_in6->sin6_family = AF_INET6; // Set family
        sin_addr_ptr = &(sa_in6->sin6_addr);
    }
    return inet_pton(family, addr, sin_addr_ptr);
}

void ProbeManager::start() {
    worker = std::thread(&ProbeManager::handler, this);
    std::future<void> start_future = start_promise.get_future();
    try {
        auto start_result = start_future.wait_for(std::chrono::seconds(10));

        if (start_result == std::future_status::timeout) {
            ALOGE("Failed to start probe manager (timeout)");
            return;
        }
    } catch (const std::exception &e) {
        ALOGE("Failed to start probe manager: %s", e.what());
    }
}

void ProbeManager::stop() {
    running.store(false);
    wakeup_event();
    worker.join();
}

// Worker thread
void ProbeManager::handler() {
    setup_epoll();
    if (epoll_fd < 0 || wakeup_fd < 0) {
        ALOGE("Error setting up epoll");
        start_promise.set_exception(std::make_exception_ptr(std::runtime_error("Error setting up epoll")));
        return;
    }
    running.store(true);
    start_promise.set_value();
    while (running.load()) {
        epoll_event events[32];
        int n = epoll_wait(epoll_fd, events, 32, get_min_wait_time());
        for (int i = 0; i < n; i++) {
            if (events[i].data.fd == wakeup_fd) {
                // Handle wakeup event
                uint64_t ev;
                read(wakeup_fd, &ev, sizeof(ev));
                continue;
            }
            // Handle socket events
            read_data(events[i].data.fd);
        }
        check_timeouts();
        send_callbacks();
        clean_probes();
    }
    force_timeouts();
    clean_probes();
    close(wakeup_fd);
    close(epoll_fd);
}

void ProbeManager::setup_epoll() {
    epoll_fd = epoll_create1(0);
    if (epoll_fd < 0) {
        ALOGE("Error creating epoll: %d %s", errno, strerror(errno));
        return;
    }
    wakeup_fd = eventfd(0, EFD_NONBLOCK);
    if (wakeup_fd < 0) {
        ALOGE("Error creating wakeup fd: %d %s", errno, strerror(errno));
        return;
    }
    epoll_event event{
            .events = EPOLLIN | EPOLLRDHUP,
            .data = {.fd = wakeup_fd}
    };
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, wakeup_fd, &event);
}

void ProbeManager::wakeup_event() const {
    int64_t one = 1;
    write(wakeup_fd, &one, sizeof(one));
}

void ProbeManager::init_packet_data(ProbeContext &probe, int size, char *pattern, int pattern_len) const {
    int data_offset = probe.probe_type == ProbeType::ICMP ? ICMP_HEADER_SIZE : 0;
    int packet_size = size;
    if (probe.probe_type == ProbeType::ICMP && size < ICMP_HEADER_SIZE)
        packet_size = ICMP_HEADER_SIZE;
    probe.packet_data.resize(packet_size);
    memset(probe.packet_data.data(), 0, packet_size);
    if (probe.probe_type == ProbeType::ICMP) {
        if (remote_addr.ss_family == AF_INET) {
            auto hdr = reinterpret_cast<struct icmphdr *>(probe.packet_data.data());
            hdr->type = ICMP_ECHO;
            hdr->code = 0;
            hdr->un.echo.id = htons(ident);
            hdr->un.echo.sequence = htons(probe.sequence);
        } else {
            auto hdr = reinterpret_cast<struct icmp6hdr *>(probe.packet_data.data());
            hdr->icmp6_type = ICMPV6_ECHO_REQUEST;
            hdr->icmp6_code = 0;
            hdr->icmp6_dataun.u_echo.identifier = htons(ident);
            hdr->icmp6_dataun.u_echo.sequence = htons(probe.sequence);
        }
    }
    // Fill packet with pattern
    if (pattern_len > 0) {
        for (int i = data_offset; i < packet_size; i += pattern_len) {
            int chunk_size = packet_size - i;
            if (chunk_size > pattern_len)
                chunk_size = pattern_len;
            memcpy(probe.packet_data.data() + i, pattern, static_cast<size_t>(chunk_size));
        }
    }
}

void ProbeManager::init_socket(int sock, ProbeContext &probe, bool detect_mtu) const {
    // TTL
    if (probe.ttl > 0) {
        if (remote_addr.ss_family == AF_INET) {
            if (setsockopt(sock, IPPROTO_IP, IP_TTL, &probe.ttl, sizeof(probe.ttl)) < 0) {
                ALOGE("Error setting TTL: %d %s", errno, strerror(errno));
            }
        } else {
            if (setsockopt(sock, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &probe.ttl, sizeof(probe.ttl)) < 0) {
                ALOGE("Error setting TTL: %d %s", errno, strerror(errno));
            }
        }
    }
    // Receive timeout
    if (probe.timeout > 0) {
        timeval tv{
                .tv_sec = MS_TO_SEC(probe.timeout),
                .tv_usec = MS_TO_USEC(probe.timeout)
        };
        if (setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
            ALOGE("Error setting receive timeout: %d %s", errno, strerror(errno));
        }
    }
    // Send timeout
    {
        timeval tv{
                .tv_sec = MS_TO_SEC(DEFAULT_SEND_TIMEOUT),
                .tv_usec = MS_TO_USEC(DEFAULT_SEND_TIMEOUT)
        };
        if (setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv)) < 0) {
            ALOGE("Error setting send timeout: %d %s", errno, strerror(errno));
        }
    }
    // Receive error
    int on = 1;
    if (remote_addr.ss_family == AF_INET) {
        if (setsockopt(sock, SOL_IP, IP_RECVERR, &on, sizeof(on)) < 0) {
            ALOGE("Error setting recverr: %d %s", errno, strerror(errno));
        }
    } else {
        if (setsockopt(sock, SOL_IPV6, IPV6_RECVERR, &on, sizeof(on)) < 0) {
            ALOGE("Error setting recverr: %d %s", errno, strerror(errno));
        }
    }
    // Receive TTL
    if (remote_addr.ss_family == AF_INET) {
        if (setsockopt(sock, SOL_IP, IP_RECVTTL, &on, sizeof(on)) < 0) {
            ALOGE("Error setting recvttl: %d %s", errno, strerror(errno));
        }
    } else {
        if (setsockopt(sock, SOL_IPV6, IPV6_RECVHOPLIMIT, &on, sizeof(on)) < 0) {
            ALOGE("Error setting recvttl: %d %s", errno, strerror(errno));
        }
    }
    // Receive MTU
    if (detect_mtu) {
        if (remote_addr.ss_family == AF_INET) {
            on = IP_PMTUDISC_PROBE;
            if (setsockopt(sock, SOL_IP, IP_MTU_DISCOVER, &on, sizeof(on)) < 0) {
                ALOGE("Error setting mtu discover: %d %s", errno, strerror(errno));
            }
        } else {
            on = IPV6_PMTUDISC_PROBE;
            if (setsockopt(sock, SOL_IPV6, IPV6_MTU_DISCOVER, &on, sizeof(on)) < 0) {
                ALOGE("Error setting mtu discover: %d %s", errno, strerror(errno));
            }
        }
    }
    {
        int tos = IPTOS_LOWDELAY;
        if (remote_addr.ss_family == AF_INET) {
            if (setsockopt(sock, IPPROTO_IP, IP_TOS, &tos, sizeof(tos)) < 0) {
                ALOGE("Error setting tos: %d %s", errno, strerror(errno));
            }
        } else {
            if (setsockopt(sock, IPPROTO_IPV6, IPV6_TCLASS, &tos, sizeof(tos)) < 0) {
                ALOGE("Error setting tos: %d %s", errno, strerror(errno));
            }
        }
    }
}

int ProbeManager::send_probe(int id, ProbeType probe_type, int port, int sequence, int ttl, int timeout,
                             int size, bool detect_mtu, char *pattern, int pattern_len) {
    ProbeContext probe{
            .id = id,
            .remote_ip = remote_ip,
            .ttl = ttl,
            .timeout = timeout,
            .overhead = (probe_type == ProbeType::UDP ? UDP_OVERHEAD : 0) +
                        (remote_addr.ss_family == AF_INET ? IPV4_OVERHEAD : IPV6_OVERHEAD),
            .probe_type = probe_type,
            .sequence = sequence % 0xffff,
    };

    int protocol = IPPROTO_UDP;
    if (probe_type == ProbeType::ICMP) {
        protocol = remote_addr.ss_family == AF_INET ? IPPROTO_ICMP : IPPROTO_ICMPV6;
    }

    int sock = socket(remote_addr.ss_family, SOCK_DGRAM, protocol);
    if (sock < 0) {
        probe.error_msg = std::string("Error creating socket: ") + strerror(errno);
        probe.status = ProbeStatus::FATAL_ERROR;
        ALOGE("Error creating socket: %d %s", errno, strerror(errno));
        trigger_callback(callback_obj, probe);
        return SEND_PROBE_ERROR;
    }

    if (!source_ip.empty()) {
        // Bind to specific source address
        socklen_t source_addr_len = source_addr.ss_family == AF_INET ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);
        if (bind(sock, reinterpret_cast<sockaddr *>(&source_addr), source_addr_len) < 0) {
            probe.error_msg = std::string("Error binding socket: ") + strerror(errno);
            probe.status = ProbeStatus::FATAL_ERROR;
            ALOGE("Error binding socket: %d %s", errno, strerror(errno));
            close(sock);
            trigger_callback(callback_obj, probe);
            return SEND_PROBE_ERROR;
        }
    }

    init_socket(sock, probe, detect_mtu);
    init_packet_data(probe, size, pattern, pattern_len);

    auto local_remote_addr = remote_addr;

    if (probe_type == ProbeType::UDP && port > 0) {
        if (remote_addr.ss_family == AF_INET) {
            auto *sa_in = reinterpret_cast<struct sockaddr_in *>(&local_remote_addr);
            sa_in->sin_port = htons(port);
        } else {
            auto *sa_in6 = reinterpret_cast<struct sockaddr_in6 *>(&local_remote_addr);
            sa_in6->sin6_port = htons(port);
        }
    }

    auto *addr = reinterpret_cast<struct sockaddr *>(&local_remote_addr);
    socklen_t addr_len =
            local_remote_addr.ss_family == AF_INET ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);
    gettimeofday(&probe.tv_sent, nullptr);

    if (sendto(sock, probe.packet_data.data(), probe.packet_data.size(), 0, addr, addr_len) < 0) {
        if (errno != EMSGSIZE) {
            ALOGE("Error sending probe: %d %s", errno, strerror(errno));
            close(sock);
            probe.error_msg = std::string("Error sending probe: ") + strerror(errno);
            probe.status = ProbeStatus::FATAL_ERROR;
            trigger_callback(callback_obj, probe);
            return SEND_PROBE_ERROR;
        }
    }

    add_socket(sock, probe);

    return SEND_PROBE_SUCCESS;
}

void ProbeManager::add_socket(int fd, ProbeContext &probe) {
    std::lock_guard lock(probes_mutex);
    probes[fd] = probe;
    epoll_event event{
            .events = EPOLLIN,
            .data = {.fd = fd}
    };
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, fd, &event);
    wakeup_event();
}

void ProbeManager::force_timeouts() {
    std::lock_guard lock(probes_mutex);
    for (auto &probe: probes) {
        if (probe.second.status == ProbeStatus::WAITING)
            probe.second.status = ProbeStatus::TIMEOUT;
    }
}

void ProbeManager::clean_probes() {
    std::lock_guard lock(probes_mutex);
    for (auto it = probes.begin(); it != probes.end();) {
        if (it->second.status != ProbeStatus::WAITING) {
            epoll_ctl(epoll_fd, EPOLL_CTL_DEL, it->first, nullptr);
            close(it->first);
            // Remove it
            it = probes.erase(it);
        } else {
            ++it;
        }
    }
}

void ProbeManager::check_timeouts() {
    std::lock_guard lock(probes_mutex);
    struct timeval tv_now{};
    gettimeofday(&tv_now, nullptr);
    for (auto &probe: probes) {
        if (probe.second.status == ProbeStatus::WAITING) {
            struct timeval tv_diff{};
            timersub(&tv_now, &probe.second.tv_sent, &tv_diff);
            if (TIMEVAL_TO_MS(tv_diff) > probe.second.timeout) {
                probe.second.status = ProbeStatus::TIMEOUT;
            }
        }
    }
}

void ProbeManager::send_callbacks() {
    std::lock_guard lock(probes_mutex);
    for (auto &probe: probes) {
        if (probe.second.status != ProbeStatus::WAITING) {
            trigger_callback(callback_obj, probe.second);
        }
    }
}

int ProbeManager::get_min_wait_time() {
    std::lock_guard lock(probes_mutex);
    int min_wait_time = -1;
    struct timeval tv_now{};
    gettimeofday(&tv_now, nullptr);

    for (auto &probe: probes) {
        if (probe.second.status == ProbeStatus::WAITING) {
            struct timeval tv_diff{};
            timersub(&tv_now, &probe.second.tv_sent, &tv_diff);
            int timeout = probe.second.timeout - TIMEVAL_TO_MS(tv_diff);
            if (min_wait_time == -1 || timeout < min_wait_time) {
                min_wait_time = timeout;
                if (min_wait_time < 0) min_wait_time = 0;
            }
        }
    }
    return min_wait_time;
}

int ProbeManager::get_queue_size() {
    std::lock_guard lock(probes_mutex);
    return static_cast<int>(probes.size());
}

void ProbeManager::read_data(int fd) {
    std::lock_guard lock(probes_mutex);
    ProbeContext &probe = probes[fd];

    gettimeofday(&probe.tv_received, nullptr);

    int flag = MSG_ERRQUEUE;
    probe.status = ProbeStatus::TIMEOUT;
    probe.reply_data.resize(INCOMING_BUFFER_SIZE);
    for (int i = 0; i < 2; i++) {
        // Step 1. Receive errors
        // Step 2. Receive data

        // Try to receive errors/control messages
        char control[1024];
        struct iovec iov{
                .iov_base = probe.reply_data.data(),
                .iov_len = probe.reply_data.size(),
        };
        struct msghdr msg{
                .msg_iov = &iov,
                .msg_iovlen = 1,
                .msg_control = control,
                .msg_controllen = sizeof(control),
        };
        auto data_len = recvmsg(fd, &msg, flag | MSG_DONTWAIT);
        if (data_len >= 0) {
            struct cmsghdr *cmsg;
            for (cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
                if ((cmsg->cmsg_level == SOL_IP && cmsg->cmsg_type == IP_RECVERR) ||
                    (cmsg->cmsg_level == SOL_IPV6 && cmsg->cmsg_type == IPV6_RECVERR)) {
                    auto *err = reinterpret_cast<struct sock_extended_err *>(CMSG_DATA(cmsg));

                    struct sockaddr *offender = SO_EE_OFFENDER(err);
                    int family = remote_addr.ss_family;
                    int addr_len = family == AF_INET ? INET_ADDRSTRLEN : INET6_ADDRSTRLEN;
                    probe.offender.resize(addr_len);
                    inet_ntop(family, family == AF_INET
                                      ? reinterpret_cast<void *>(&reinterpret_cast<struct sockaddr_in *>(offender)->sin_addr)
                                      : reinterpret_cast<void *>(&reinterpret_cast<struct sockaddr_in6 *>(offender)->sin6_addr),
                              probe.offender.data(),
                              addr_len);

                    probe.err_no = err->ee_errno;
                    probe.err_code = err->ee_code;
                    probe.err_type = err->ee_origin;
                    probe.err_info = err->ee_info;
                    probe.status = ProbeStatus::ERROR;
                    ioctl(fd, SIOCGSTAMP, &probe.tv_received);
                } else if ((cmsg->cmsg_level == SOL_IP && cmsg->cmsg_type == IP_TTL) ||
                           (cmsg->cmsg_level == SOL_IPV6 && cmsg->cmsg_type == IPV6_HOPLIMIT)) {
                    probe.reply_ttl = *reinterpret_cast<int *>(CMSG_DATA(cmsg));
                }
            }
            if (flag == 0) {
                // Got response
                probe.status = ProbeStatus::SUCCESS;
                ioctl(fd, SIOCGSTAMP, &probe.tv_received);
                probe.reply_data.resize(data_len);
            }
        }
        if (probe.status == ProbeStatus::ERROR) {
            // We got what we need, no need to continue
            break;
        }
        flag = 0;
    }
    // Calculate time difference
    timersub(&probe.tv_received, &probe.tv_sent, &probe.tv_diff);
}

// JNI stuff
extern "C" {

JavaVM *java_vm = nullptr;

void trigger_callback(void *obj, ProbeContext &probe) {
    if (java_vm == nullptr || obj == nullptr || CALLBACK_CLS == nullptr || CALLBACK_MID == nullptr) {
        ALOGE("JNI not initialized properly");
        return;
    }
    JNIEnv *env;
    bool attached = false;
    auto getEnvStat = java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (java_vm->AttachCurrentThread(&env, nullptr) != 0) {
            ALOGE("Failed to attach current thread");
            return;
        }
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        ALOGE("Failed to get JNI environment");
        return;
    }

    auto remote_ip = env->NewStringUTF(probe.remote_ip.c_str());

    jobject res_data = nullptr;

    switch (probe.status) {
        case ProbeStatus::FATAL_ERROR: {
            auto err_msg = env->NewStringUTF(probe.error_msg.c_str());
            res_data = env->NewObject(RESULT_UNKNOWN_CLS, RESULT_UNKNOWN_MID, probe.sequence, remote_ip,
                                      probe.packet_data.size(), probe.overhead, err_msg);
            env->DeleteLocalRef(err_msg);
        }
            break;
        case ProbeStatus::SUCCESS: {
            auto packet_data = env->NewByteArray(static_cast<jint>(probe.reply_data.size()));
            env->SetByteArrayRegion(packet_data, 0, static_cast<jsize>(probe.reply_data.size()),
                                    reinterpret_cast<const jbyte *>(probe.reply_data.data()));
            res_data = env->NewObject(RESULT_SUCCESS_CLS, RESULT_SUCCESS_MID, probe.sequence, remote_ip,
                                      probe.packet_data.size(), probe.overhead, TIMEVAL_TO_USEC(probe.tv_diff),
                                      probe.reply_ttl, packet_data);
            env->DeleteLocalRef(packet_data);
        }
            break;
        case ProbeStatus::TIMEOUT:
            res_data = env->NewObject(RESULT_TIMEOUT_CLS, RESULT_TIMEOUT_MID, probe.sequence, remote_ip,
                                      probe.packet_data.size(), probe.overhead);
            break;
        case ProbeStatus::ERROR: {
            auto offender = env->NewStringUTF(probe.offender.c_str());
            switch (probe.err_no) {
                case ECONNREFUSED:
                    res_data = env->NewObject(RESULT_CONNECTION_REFUSED_CLS, RESULT_CONNECTION_REFUSED_MID,
                                              probe.sequence, remote_ip, probe.packet_data.size(), probe.overhead,
                                              offender,
                                              TIMEVAL_TO_USEC(probe.tv_diff));
                    break;
                case EHOSTUNREACH:
                    res_data = env->NewObject(RESULT_HOST_UNREACHABLE_CLS, RESULT_HOST_UNREACHABLE_MID,
                                              probe.sequence, remote_ip, probe.packet_data.size(), probe.overhead,
                                              offender,
                                              TIMEVAL_TO_USEC(probe.tv_diff));
                    break;
                case ENETUNREACH:
                    res_data = env->NewObject(RESULT_NET_UNREACHABLE_CLS, RESULT_NET_UNREACHABLE_MID,
                                              probe.sequence, remote_ip, probe.packet_data.size(), probe.overhead,
                                              offender,
                                              TIMEVAL_TO_USEC(probe.tv_diff));
                    break;
                default:
                    res_data = env->NewObject(RESULT_NET_ERROR_CLS, RESULT_NET_ERROR_MID, probe.sequence, remote_ip,
                                              probe.packet_data.size(), probe.overhead, offender,
                                              static_cast<jint>(probe.err_no),
                                              probe.err_code, probe.err_type, probe.err_info);
                    break;
            }
            env->DeleteLocalRef(offender);
        }
            break;
        default:
        ALOGE("Unknown probe status: %d", probe.status);
            break;
    }

    env->DeleteLocalRef(remote_ip);

    if (res_data == nullptr) {
        ALOGE("Failed to create result data");
    } else {
        env->CallVoidMethod(reinterpret_cast<jobject>(obj), CALLBACK_MID, probe.id, res_data);
        env->DeleteLocalRef(res_data);
    }

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) {
        java_vm->DetachCurrentThread();
    }
}

void clear_jni_global_refs(JNIEnv *env) {
    for (int i = 0; i < JNI_METHOD_COUNT; i++) {
        if (JNI_METHOD(i).cls != nullptr) {
            env->DeleteGlobalRef(JNI_METHOD(i).cls);
            JNI_METHOD(i).cls = nullptr;
        }

    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    java_vm = vm;
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("Failed to get JNI environment");
        return JNI_ERR;
    }

    for (int i = 0; i < JNI_METHOD_COUNT; i++) {
        jclass cls = env->FindClass(JNI_METHOD(i).class_name);
        if (cls == nullptr) {
            ALOGE("Failed to find class %s", JNI_METHOD(i).class_name);
            return JNI_ERR;
        }
        JNI_METHOD(i).cls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);
        JNI_METHOD(i).mid = env->GetMethodID(JNI_METHOD(i).cls, JNI_METHOD(i).method_name, JNI_METHOD(i).method_sig);
        if (JNI_METHOD(i).mid == nullptr) {
            ALOGE("Failed to find method %s", JNI_METHOD(i).method_name);
            clear_jni_global_refs(env);
            return JNI_ERR;
        }
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;
    }
    clear_jni_global_refs(env);
}

JNIEXPORT jlong JNICALL
Java_me_impa_icmpenguin_ProbeManager_create(JNIEnv *env, jobject thiz, jstring remote_ip, jstring source_ip) {
    const char *remote_ip_str = env->GetStringUTFChars(remote_ip, nullptr);
    const char *source_ip_str = env->GetStringUTFChars(source_ip, nullptr);

#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
    // No, dear clang, this is not a leak.
    // The lifetime of this class is managed by Kotlin through a descriptor.
    auto *manager = new ProbeManager(remote_ip_str, source_ip_str,
                                     env->NewGlobalRef(thiz), trigger_callback);

#pragma clang diagnostic pop
    manager->start();
    env->ReleaseStringUTFChars(source_ip, source_ip_str);
    env->ReleaseStringUTFChars(remote_ip, remote_ip_str);
    return reinterpret_cast<jlong>(manager);
}

JNIEXPORT void JNICALL Java_me_impa_icmpenguin_ProbeManager_delete(JNIEnv *env, jobject /*thiz*/, jlong ptr) {
    auto *manager = reinterpret_cast<ProbeManager *>(ptr);
    manager->stop();
    env->DeleteGlobalRef(reinterpret_cast<jobject>(manager->get_callback_obj()));
    delete manager;
}

JNIEXPORT jint JNICALL Java_me_impa_icmpenguin_ProbeManager_sendProbe(JNIEnv *env, jobject /*thiz*/,
                                                                      jlong ptr, jint id, jint probe_type, jint port,
                                                                      jint sequence, jint ttl, jint timeout,
                                                                      jint size, jboolean detect_mtu,
                                                                      jbyteArray pattern) {
    auto *manager = reinterpret_cast<ProbeManager *>(ptr);
    jbyte *pattern_bytes = env->GetByteArrayElements(pattern, nullptr);
    int pattern_len = env->GetArrayLength(pattern);
    int res = manager->send_probe(id, static_cast<ProbeType>(probe_type), port, sequence, ttl, timeout, size,
                                  detect_mtu, (char *) pattern_bytes,
                                  pattern_len);
    env->ReleaseByteArrayElements(pattern, pattern_bytes, JNI_ABORT);
    return res;
}

JNIEXPORT jint JNICALL
Java_me_impa_icmpenguin_ProbeManager_getQueueSize([[maybe_unused]] JNIEnv *env, jobject /*thiz*/, jlong ptr) {
    auto *manager = reinterpret_cast<ProbeManager *>(ptr);
    return manager->get_queue_size();
}

}

