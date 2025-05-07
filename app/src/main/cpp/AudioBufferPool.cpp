//
// Created by ducng on 5/6/2025.
//

#include "AudioBufferPool.h"
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AudioBufferPool", __VA_ARGS__)

AudioBufferPool::AudioBufferPool(int sampleRate, int channels)
        : sampleRate(sampleRate), channels(channels),
          samplesPer2Sec(sampleRate * channels * 2),
          threadPool(2) // số worker tùy chọn
{
}

void AudioBufferPool::push(const int16_t* data, int samples, int64_t ptsUs) {
    currentBuffer.insert(currentBuffer.end(), data, data + samples);

    while (currentBuffer.size() >= samplesPer2Sec) {
        auto block = acquire();
        block->data.assign(currentBuffer.begin(), currentBuffer.begin() + samplesPer2Sec);
        block->ptsUs = ptsUs - (samplesPer2Sec * 1000000LL / (sampleRate * channels));
        LOGI("add block ");
        currentBuffer.erase(currentBuffer.begin(), currentBuffer.begin() + samplesPer2Sec);

        bufferBlocks.push_back(block);
        classifyIfReady();
    }
}

void AudioBufferPool::flush() {
    LOGI("flushing");
    std::shared_ptr<PCMBuffer> buf;
    {
        std::lock_guard<std::mutex> lock(poolMutex);
        if (!currentBuffer.empty()) {
            // unlock trước khi acquire
        }
    }

    // acquire ngoài lock để tránh deadlock
    if (!currentBuffer.empty()) {
        buf = acquire();
        LOGI("move");
        buf->data = std::move(currentBuffer);
        buf->ptsUs = 0;
        currentBuffer.clear();
    }

    std::vector<std::shared_ptr<PCMBuffer>> toClassify;
    {
        std::lock_guard<std::mutex> lock(poolMutex);
        if (buf) {
            LOGI("push back");
            bufferBlocks.push_back(buf);
        }
        if (!bufferBlocks.empty()) {
            toClassify.swap(bufferBlocks);
            LOGI("toClassify.swap %d", toClassify.size());
        }
    }

    if (!toClassify.empty()) {
        threadPool.enqueue([this, toClassify]() {
            doClassification(toClassify);
            for (auto buf : toClassify) release(buf);
        });
    }
    LOGI("end flushing....");
}

void AudioBufferPool::classifyIfReady() {
    std::lock_guard<std::mutex> lock(poolMutex);
    LOGI("classifyIfReady %d", bufferBlocks.size());
    if (!bufferBlocks.empty()) {
        std::vector<std::shared_ptr<PCMBuffer>> toClassify(bufferBlocks.begin(), bufferBlocks.begin() + 1);
        bufferBlocks.erase(bufferBlocks.begin(), bufferBlocks.begin() + 1);

        threadPool.enqueue([this, toClassify]() {
            doClassification(toClassify);

            // release blocks
            for (auto buf : toClassify) release(buf);
        });
    }
}

std::shared_ptr<PCMBuffer> AudioBufferPool::acquire() {
    std::lock_guard<std::mutex> lock(poolMutex);
    if (!reusePool.empty()) {
        auto buf = reusePool.front();
        reusePool.pop();
        buf->clear();
        return buf;
    }
    return std::make_shared<PCMBuffer>();
}

void AudioBufferPool::release(std::shared_ptr<PCMBuffer>& buf) {
    std::lock_guard<std::mutex> lock(poolMutex);
    reusePool.push(buf);
}

void AudioBufferPool::doClassification(std::vector<std::shared_ptr<PCMBuffer>> blocks) {
    // Ghép 4 block lại
    std::vector<int16_t> combined;
    for (const auto& b : blocks) {
        combined.insert(combined.end(), b->data.begin(), b->data.end());
    }

    // TODO: Gọi lib classification native
    LOGI("Classifying 8s audio, size: %zu samples", combined.size());

    // TODO: Trả kết quả về Java nếu cần
}

