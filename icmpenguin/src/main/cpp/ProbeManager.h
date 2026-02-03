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

#ifndef ICMPENGUIN_PROBEMANAGER_H
#define ICMPENGUIN_PROBEMANAGER_H

#import <netinet/in.h>
#import <string>
#import <thread>
#import <atomic>
#import <future>

#define SEND_PROBE_ERROR (-1)
#define SEND_PROBE_SUCCESS 0

#define ICMP_HEADER_SIZE 8
#define INCOMING_BUFFER_SIZE 2048

#define DEFAULT_SEND_TIMEOUT 1000

#define IPV4_OVERHEAD 20
#define IPV6_OVERHEAD 40
#define UDP_OVERHEAD 8

#define MS_TO_SEC(x) (x/1000)
#define MS_TO_USEC(x) ((x%1000)*1000)
#define TIMEVAL_TO_MS(x) ((x.tv_sec*1000)+(x.tv_usec/1000))
#define TIMEVAL_TO_USEC(x) ((x.tv_sec*1000000)+(x.tv_usec))

enum class ProbeType {
    ICMP = 1, UDP = 2
};
enum class ProbeStatus {
    WAITING = 0, SUCCESS = 1, TIMEOUT = 2, ERROR = 3, FATAL_ERROR = -1
};

struct ProbeContext {
    int id;
    std::string remote_ip;
    std::string offender;
    std::vector<uint8_t> packet_data;
    std::vector<uint8_t> reply_data;
    int ttl;
    int reply_ttl;
    int timeout;
    int overhead;
    ProbeType probe_type;
    struct timeval tv_sent;
    struct timeval tv_received;
    struct timeval tv_diff;
    int sequence;
    std::string error_msg;
    unsigned int err_no;
    int err_code;
    int err_type;
    unsigned int err_info;
    ProbeStatus status = ProbeStatus::WAITING;
};

using JNICallback = std::function<void(void *, ProbeContext &)>;

class ProbeManager {
private:

    void *callback_obj = nullptr;
    JNICallback trigger_callback;

    std::unordered_map<int, ProbeContext> probes;
    std::mutex probes_mutex;
    int ident;
    struct sockaddr_storage remote_addr{};
    struct sockaddr_storage source_addr{};
    std::string remote_ip;
    std::string source_ip;
    std::thread worker;
    std::atomic<bool> running{false};
    int epoll_fd = -1;
    int wakeup_fd = -1;
    std::promise<void> start_promise;

    int try_init_addr(int family, const char *addr, sockaddr_storage &addr_storage);

    void init_packet_data(ProbeContext &probe, int size, char *pattern, int pattern_len) const;

    void init_socket(int sock, ProbeContext &probe, bool detect_mtu) const;

    void add_socket(int fd, ProbeContext &probe);

    void check_timeouts();

    void clean_probes();

    void send_callbacks();

    void force_timeouts();

    int get_min_wait_time();

    void read_data(int fd);

    void wakeup_event() const;

    void setup_epoll();

    void handler();

public:

    explicit ProbeManager(const char *remote_ip, const char *source_ip, void *callback_obj, JNICallback trigger_callback);

    void start();

    void stop();

    int
    send_probe(int id, ProbeType probe_type, int port, int sequence, int ttl, int timeout, int size, bool detect_mtu,
               char *pattern, int pattern_len);

    int get_queue_size();

    void *get_callback_obj() { return callback_obj; }
};


#endif //ICMPENGUIN_PROBEMANAGER_H
