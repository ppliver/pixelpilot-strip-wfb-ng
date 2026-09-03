//
// Created by gaeta on 2024-04-01.
//

#include "UdpReceiver.h"
#include <arpa/inet.h>
#include <array>
#include <sstream>
#include <utility>
#include <vector>

#include "AndroidThreadPrioValues.hpp"
#include "helper/AndroidLogger.hpp"
#include "helper/NDKThreadHelper.hpp"
#include "helper/StringHelper.hpp"

namespace
{
/**
 * Build a dual-stack (IPv6 + IPv4-mapped) UDP socket.
 * AF_INET6 with IPV6_V6ONLY=0 accepts IPv6 and IPv4 traffic on the same port.
 */
int openDualStackSocket()
{
    const int fd = socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    if (fd == -1)
    {
        return -1;
    }
    int v6only = 0;
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only)) < 0)
    {
        MLOGD << "Could not enable IPv4/IPv6 dual stack: " << strerror(errno);
    }
    return fd;
}

/** Render a sockaddr as a printable IP (v4-mapped addresses print as dotted quad). */
std::string addrToString(const struct sockaddr_storage* ss)
{
    char  buf[INET6_ADDRSTRLEN];
    int   af  = AF_UNSPEC;
    const void* src = nullptr;

    if (ss->ss_family == AF_INET)
    {
        af  = AF_INET;
        src = &reinterpret_cast<const struct sockaddr_in*>(ss)->sin_addr;
    }
    else if (ss->ss_family == AF_INET6)
    {
        const struct in6_addr* a6 = &reinterpret_cast<const struct sockaddr_in6*>(ss)->sin6_addr;
        const unsigned char* b = a6->s6_addr;
        bool v4mapped = true;
        for (int i = 0; i < 10; i++)
        {
            if (b[i] != 0) { v4mapped = false; break; }
        }
        if (v4mapped && b[10] == 0xff && b[11] == 0xff)
        {
            af  = AF_INET;
            src = b + 12;
        }
        else
        {
            af  = AF_INET6;
            src = a6;
        }
    }
    else
    {
        return "0.0.0.0";
    }

    if (inet_ntop(af, src, buf, sizeof(buf)) == nullptr)
    {
        return "0.0.0.0";
    }
    return std::string(buf);
}

/** Fill a sockaddr_in6 from an IP literal, mapping IPv4 into ::ffff:a.b.c.d. */
bool parseForwardTarget(const std::string& ip, int port, struct sockaddr_storage& out, socklen_t& outLen)
{
    struct sockaddr_in6 t {};
    t.sin6_family = AF_INET6;
    t.sin6_port   = htons(static_cast<uint16_t>(port));

    if (inet_pton(AF_INET6, ip.c_str(), &t.sin6_addr) != 1)
    {
        struct in_addr v4 {};
        if (inet_pton(AF_INET, ip.c_str(), &v4) != 1)
        {
            return false;
        }
        uint8_t* b = t.sin6_addr.s6_addr;
        memset(b, 0, 10);
        b[10] = 0xff;
        b[11] = 0xff;
        memcpy(b + 12, &v4, 4);
    }
    memset(&out, 0, sizeof(out));
    memcpy(&out, &t, sizeof(t));
    outLen = sizeof(t);
    return true;
}
}  // namespace

UDPReceiver::UDPReceiver(
    JavaVM*       javaVm,
    int           port,
    std::string   name,
    int           CPUPriority,
    DATA_CALLBACK onDataReceivedCallback,
    size_t        WANTED_RCVBUF_SIZE)
    : mPort(port),
      mName(std::move(name)),
      WANTED_RCVBUF_SIZE(WANTED_RCVBUF_SIZE),
      mCPUPriority(CPUPriority),
      onDataReceivedCallback(std::move(onDataReceivedCallback)),
      javaVm(javaVm)
{
}

void UDPReceiver::registerOnSourceIPFound(SOURCE_IP_CALLBACK onSourceIP1)
{
    this->onSourceIP = std::move(onSourceIP1);
}

long UDPReceiver::getNReceivedBytes() const
{
    return nReceivedBytes;
}

std::string UDPReceiver::getSourceIPAddress() const
{
    return senderIP;
}

void UDPReceiver::startReceiving()
{
    receiving          = true;
    mUDPReceiverThread = std::make_unique<std::thread>([this] { this->receiveFromUDPLoop(); });
#ifdef __ANDROID__
    NDKThreadHelper::setName(mUDPReceiverThread->native_handle(), mName.c_str());
#endif
}

void UDPReceiver::stopReceiving()
{
    receiving = false;
    // this stops the recvfrom even if in blocking mode
    shutdown(mSocket, SHUT_RD);
    if (mUDPReceiverThread->joinable())
    {
        mUDPReceiverThread->join();
    }
    mUDPReceiverThread.reset();
}

void UDPReceiver::receiveFromUDPLoop()
{
    // Dual-stack: listens on IPv6 and IPv4 (v4-mapped) at the same port.
    mSocket = openDualStackSocket();
    if (mSocket == -1)
    {
        MLOGD << "Error creating socket";
        return;
    }
    int enable = 1;
    if (setsockopt(mSocket, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(int)) < 0)
    {
        MLOGD << "Error setting reuse";
    }
    int       recvBufferSize = 0;
    socklen_t len            = sizeof(recvBufferSize);
    getsockopt(mSocket, SOL_SOCKET, SO_RCVBUF, &recvBufferSize, &len);
    MLOGD << "Default socket recv buffer is " << StringHelper::memorySizeReadable(recvBufferSize);

    if (WANTED_RCVBUF_SIZE > recvBufferSize)
    {
        recvBufferSize = WANTED_RCVBUF_SIZE;
        if (setsockopt(mSocket, SOL_SOCKET, SO_RCVBUF, &WANTED_RCVBUF_SIZE, len))
        {
            MLOGD << "Cannot increase buffer size to " << StringHelper::memorySizeReadable(WANTED_RCVBUF_SIZE);
        }
        getsockopt(mSocket, SOL_SOCKET, SO_RCVBUF, &recvBufferSize, &len);
        MLOGD << "Wanted " << StringHelper::memorySizeReadable(WANTED_RCVBUF_SIZE) << " Set "
              << StringHelper::memorySizeReadable(recvBufferSize);
    }
    if (javaVm != nullptr)
    {
#ifdef __ANDROID__
        NDKThreadHelper::setProcessThreadPriorityAttachDetach(javaVm, mCPUPriority, mName.c_str());
#endif
    }
    struct sockaddr_in6 myaddr;
    memset((uint8_t*) &myaddr, 0, sizeof(myaddr));
    myaddr.sin6_family = AF_INET6;
    myaddr.sin6_addr   = in6addr_any;
    myaddr.sin6_port   = htons(mPort);
    if (bind(mSocket, (struct sockaddr*) &myaddr, sizeof(myaddr)) == -1)
    {
        MLOGE << "Error binding Port; " << mPort;
        return;
    }
    // wrap into unique pointer to avoid running out of stack
    const auto buff = std::make_unique<std::array<uint8_t, UDP_PACKET_MAX_SIZE>>();

    MLOGD << "Listening on " << INADDR_ANY << ":" << mPort;

    sockaddr_storage source;
    socklen_t         sourceLen = sizeof(sockaddr_storage);

    while (receiving)
    {
        // recvfrom() overwrites sourceLen, so it must be reset for every call.
        sourceLen = sizeof(source);
        // TODO investigate: does a big buffer size create latency with MSG_WAITALL ?
        // I do not think so. recvfrom should return as soon as new data arrived,not when the buffer is full
        // But with a bigger buffer we do not loose packets when the receiver thread cannot keep up for a short amount
        // of time
        //  MSG_WAITALL does not wait until we have __n data, but a new UDP packet (that can be smaller than __n)
        const ssize_t message_length =
            recvfrom(mSocket, buff->data(), UDP_PACKET_MAX_SIZE, 0, (sockaddr*) &source, &sourceLen);
        // ssize_t message_length = recv(mSocket, buff, (size_t) mBuffsize, MSG_WAITALL);
        if (message_length > 0)
        {  // else -1 was returned;timeout/No data received
            // 1. Forward packet first (minimize latency)
            {
                std::lock_guard<std::mutex> lock(mForwardMutex);
                if (mForwardEnabled)
                {
                    sendto(mSocket, buff->data(), message_length, 0,
                           (struct sockaddr*) &mDestAddr, mDestAddrLen);
                }
            }

            // 2. Local processing
            onDataReceivedCallback(buff->data(), (size_t) message_length);

            nReceivedBytes += message_length;
            // The source ip stuff (IPv6 aware; v4-mapped prints as dotted quad)
            std::string s1 = addrToString(&source);
            if (senderIP != s1)
            {
                senderIP = s1;
            }
            if (onSourceIP != nullptr)
            {
                onSourceIP(s1);
            }
        }
        else
        {
            if (errno != EWOULDBLOCK)
            {
                MLOGE << "Error on recvfrom. errno=" << errno << " " << strerror(errno);
            }
        }
    }
    close(mSocket);
}

int UDPReceiver::getPort() const
{
    return mPort;
}

void UDPReceiver::setForwarding(const std::string& ip, int port, bool enabled)
{
    std::lock_guard<std::mutex> lock(mForwardMutex);
    mForwardIP = ip;
    mForwardPort = port;
    mForwardEnabled = enabled;

    // Accepts IPv4 literals (mapped to ::ffff:a.b.c.d) and IPv6 literals alike.
    if (!parseForwardTarget(ip, port, mDestAddr, mDestAddrLen))
    {
        MLOGE << "Cannot parse forwarding target " << ip << ":" << port;
        mForwardEnabled = false;
    }
}
