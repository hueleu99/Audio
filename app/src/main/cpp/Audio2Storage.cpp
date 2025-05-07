//
// Created by ducng on 5/7/2025.
//

#include "Audio2Storage.h"

void Audio2Storage::append(const int16_t* data, int samples, int64_t ptsUs) {
    PCMItem item;
    item.ptsUs = ptsUs;
    item.data.assign(data, data + samples);

    std::lock_guard<std::mutex> lock(mutex);
    buffer.push_back(std::move(item));
}

std::vector<int16_t> Audio2Storage::getRange(int64_t startUs, int64_t endUs, int sampleRate, int channels) {
    std::lock_guard<std::mutex> lock(mutex);

    std::vector<int16_t> result;

    for (const auto& item : buffer) {
        int64_t itemStart = item.ptsUs;
        int64_t itemEnd = itemStart + item.data.size() * 1000000LL / (sampleRate * channels);

        if (itemEnd < startUs) continue;
        if (itemStart > endUs) break;

        result.insert(result.end(), item.data.begin(), item.data.end());
    }

    return result;
}

