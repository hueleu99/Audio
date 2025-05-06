//
// Created by ducng on 5/6/2025.
//

#ifndef AUDIODETECTOR_AUDIOBUFFERPOOL_H
#define AUDIODETECTOR_AUDIOBUFFERPOOL_H


#pragma once
#include "PCMBuffer.h"
#include "ThreadPool.h"
#include <vector>
#include <queue>
#include <mutex>

class AudioBufferPool {
public:
    AudioBufferPool(int sampleRate, int channels);

    void push(const int16_t* data, int samples, int64_t ptsUs);

    void flush();

private:
    int sampleRate, channels;
    size_t samplesPer2Sec;

    std::vector<std::shared_ptr<PCMBuffer>> bufferBlocks;
    std::vector<int16_t> currentBuffer;

    std::queue<std::shared_ptr<PCMBuffer>> reusePool;
    std::mutex poolMutex;

    ThreadPool threadPool;

    std::shared_ptr<PCMBuffer> acquire();
    void release(std::shared_ptr<PCMBuffer>& buf);
    void classifyIfReady();
    void doClassification(std::vector<std::shared_ptr<PCMBuffer>> blocks);
};



#endif //AUDIODETECTOR_AUDIOBUFFERPOOL_H
