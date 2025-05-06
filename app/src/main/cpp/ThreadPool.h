//
// Created by ducng on 5/6/2025.
//

#ifndef AUDIODETECTOR_THREADPOOL_H
#define AUDIODETECTOR_THREADPOOL_H


#pragma once
#include <functional>
#include <thread>
#include <vector>
#include <queue>
#include <mutex>
#include <condition_variable>

class ThreadPool {
public:
    explicit ThreadPool(size_t numThreads);
    ~ThreadPool();

    void enqueue(const std::function<void()>& task);

private:
    std::vector<std::thread> workers;
    std::queue<std::function<void()>> tasks;

    std::mutex queueMutex;
    std::condition_variable condition;
    bool stop = false;
};


#endif //AUDIODETECTOR_THREADPOOL_H
