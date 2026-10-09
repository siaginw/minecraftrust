// Isolated Direct3D 11 experiment; no RustCraft runtime imports or world changes.
#define NOMINMAX
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <dxgi.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <immintrin.h>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
using Microsoft::WRL::ComPtr;
using Clock = std::chrono::steady_clock;
static double milliseconds(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
static void check(HRESULT hr, const char* operation) {
    if (FAILED(hr)) throw std::runtime_error(std::string(operation) + " HRESULT=" + std::to_string(hr));
}
static void cpu(const uint32_t* input, uint32_t* output, size_t start, size_t end, uint32_t rounds, uint32_t seed) {
    for (size_t i = start; i < end; ++i) {
        uint32_t value = input[i];
        for (uint32_t r = 0; r < rounds; ++r) {
            value ^= value >> 16; value *= 0x7feb352dU;
            value ^= value >> 15; value *= 0x846ca68bU;
            value ^= value >> 16; value += seed + r;
        }
        output[i] = value;
    }
}
static void cpu_optimized(const uint32_t* input, uint32_t* output, size_t start, size_t end, uint32_t rounds, uint32_t seed) {
    size_t i = start;
#if defined(__AVX2__)
    const auto a = _mm256_set1_epi32(0x7feb352d);
    const auto b = _mm256_set1_epi32(static_cast<int>(0x846ca68bU));
    for (; i + 8 <= end; i += 8) {
        auto value = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(input + i));
        for (uint32_t r = 0; r < rounds; ++r) {
            value = _mm256_xor_si256(value, _mm256_srli_epi32(value, 16));
            value = _mm256_mullo_epi32(value, a);
            value = _mm256_xor_si256(value, _mm256_srli_epi32(value, 15));
            value = _mm256_mullo_epi32(value, b);
            value = _mm256_xor_si256(value, _mm256_srli_epi32(value, 16));
            value = _mm256_add_epi32(value, _mm256_set1_epi32(static_cast<int>(seed + r)));
        }
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(output + i), value);
    }
#endif
    cpu(input, output, i, end, rounds, seed);
}
class Pool {
    std::mutex mutex;
    std::condition_variable work, done;
    std::vector<std::thread> threads;
    bool stop = false;
    size_t epoch = 0, completed = 0, count = 0;
    const uint32_t* input = nullptr;
    uint32_t* output = nullptr;
    uint32_t rounds = 0, seed = 0;
public:
    explicit Pool(size_t workers) {
        for (size_t worker = 0; worker < workers; ++worker) threads.emplace_back([this, worker, workers] {
            size_t seen = 0;
            std::unique_lock<std::mutex> lock(mutex);
            while (true) {
                work.wait(lock, [&] { return stop || epoch != seen; });
                if (stop) return;
                seen = epoch;
                const auto begin = count * worker / workers, end = count * (worker + 1) / workers;
                // Job fields remain immutable until all workers finish.
                lock.unlock(); cpu_optimized(input, output, begin, end, rounds, seed); lock.lock();
                if (++completed == workers) done.notify_one();
            }
        });
    }
    ~Pool() {
        { std::lock_guard<std::mutex> lock(mutex); stop = true; }
        work.notify_all();
        for (auto& thread : threads) thread.join();
    }
    void run(const std::vector<uint32_t>& in, std::vector<uint32_t>& out, uint32_t r, uint32_t s) {
        std::unique_lock<std::mutex> lock(mutex);
        input = in.data(); output = out.data(); count = in.size(); rounds = r; seed = s;
        completed = 0; ++epoch; work.notify_all();
        done.wait(lock, [&] { return completed == threads.size(); });
    }
};
struct Gpu {
    struct Timings { double whole, kernel, wrapper; };
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11ComputeShader> shader;
    ComPtr<ID3D11Buffer> input, output, staging, parameters;
    ComPtr<ID3D11ShaderResourceView> srv;
    ComPtr<ID3D11UnorderedAccessView> uav;
    ComPtr<ID3D11Query> disjoint, begin, end;
    double device_ms = 0;
    explicit Gpu(const wchar_t* source) {
        const auto start = Clock::now();
        ComPtr<IDXGIFactory1> factory;
        check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "factory");
        ComPtr<IDXGIAdapter1> selected;
        DXGI_ADAPTER_DESC1 description{};
        for (UINT i = 0;; ++i) {
            ComPtr<IDXGIAdapter1> adapter;
            const auto hr = factory->EnumAdapters1(i, &adapter);
            if (hr == DXGI_ERROR_NOT_FOUND) break;
            check(hr, "adapter enumeration");
            DXGI_ADAPTER_DESC1 d{}; check(adapter->GetDesc1(&d), "adapter identity");
            if (!(d.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) && (!selected || d.DedicatedVideoMemory > description.DedicatedVideoMemory)) {
                selected = adapter; description = d;
            }
        }
        if (!selected) throw std::runtime_error("no hardware adapter; software fallback forbidden");
        D3D_FEATURE_LEVEL obtained;
        const D3D_FEATURE_LEVEL required[] = {D3D_FEATURE_LEVEL_11_0};
        check(D3D11CreateDevice(selected.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, required, 1,
            D3D11_SDK_VERSION, &device, &obtained, &context), "device feature level 11");
        ComPtr<ID3DBlob> bytes, errors;
        const auto compiled = D3DCompileFromFile(source, nullptr, nullptr, "main", "cs_5_0",
            D3DCOMPILE_OPTIMIZATION_LEVEL3 | D3DCOMPILE_WARNINGS_ARE_ERRORS, 0, &bytes, &errors);
        if (errors) std::cerr.write(static_cast<const char*>(errors->GetBufferPointer()), errors->GetBufferSize());
        check(compiled, "compile shader");
        check(device->CreateComputeShader(bytes->GetBufferPointer(), bytes->GetBufferSize(), nullptr, &shader), "shader");
        D3D11_QUERY_DESC q{D3D11_QUERY_TIMESTAMP_DISJOINT, 0}; check(device->CreateQuery(&q, &disjoint), "disjoint query");
        q.Query = D3D11_QUERY_TIMESTAMP; check(device->CreateQuery(&q, &begin), "start query"); check(device->CreateQuery(&q, &end), "end query");
        device_ms = milliseconds(start);
        char name[256]{}; WideCharToMultiByte(CP_UTF8, 0, description.Description, -1, name, sizeof(name), nullptr, nullptr);
        std::cout << "{\"kind\":\"device\",\"name\":\"" << name << "\",\"vendor\":" << description.VendorId
            << ",\"device\":" << description.DeviceId << ",\"dedicated_bytes\":" << description.DedicatedVideoMemory
            << ",\"device_and_shader_ms\":" << device_ms << "}\n";
    }
    double allocate(UINT count) {
        const auto start = Clock::now();
        ID3D11ShaderResourceView* empty_srv = nullptr; ID3D11UnorderedAccessView* empty_uav = nullptr;
        context->CSSetShaderResources(0, 1, &empty_srv); context->CSSetUnorderedAccessViews(0, 1, &empty_uav, nullptr);
        input.Reset(); output.Reset(); staging.Reset(); parameters.Reset(); srv.Reset(); uav.Reset();
        D3D11_BUFFER_DESC d{}; d.ByteWidth = count * 4; d.Usage = D3D11_USAGE_DEFAULT;
        d.BindFlags = D3D11_BIND_SHADER_RESOURCE; d.MiscFlags = D3D11_RESOURCE_MISC_BUFFER_STRUCTURED; d.StructureByteStride = 4;
        check(device->CreateBuffer(&d, nullptr, &input), "input buffer");
        d.BindFlags = D3D11_BIND_UNORDERED_ACCESS; check(device->CreateBuffer(&d, nullptr, &output), "output buffer");
        d.BindFlags = 0; d.MiscFlags = 0; d.StructureByteStride = 0; d.Usage = D3D11_USAGE_STAGING; d.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        check(device->CreateBuffer(&d, nullptr, &staging), "staging buffer");
        d = {}; d.ByteWidth = 16; d.Usage = D3D11_USAGE_DEFAULT; d.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        check(device->CreateBuffer(&d, nullptr, &parameters), "parameters");
        D3D11_SHADER_RESOURCE_VIEW_DESC sd{}; sd.ViewDimension = D3D11_SRV_DIMENSION_BUFFER; sd.Buffer.NumElements = count;
        check(device->CreateShaderResourceView(input.Get(), &sd, &srv), "input view");
        D3D11_UNORDERED_ACCESS_VIEW_DESC ud{}; ud.ViewDimension = D3D11_UAV_DIMENSION_BUFFER; ud.Buffer.NumElements = count;
        check(device->CreateUnorderedAccessView(output.Get(), &ud, &uav), "output view");
        context->CSSetShader(shader.Get(), nullptr, 0);
        context->CSSetShaderResources(0, 1, srv.GetAddressOf()); context->CSSetUnorderedAccessViews(0, 1, uav.GetAddressOf(), nullptr);
        context->CSSetConstantBuffers(0, 1, parameters.GetAddressOf());
        return milliseconds(start);
    }
    template<class T> void query(ID3D11Query* q, T& value) {
        const auto start = Clock::now();
        for (;;) {
            const auto hr = context->GetData(q, &value, sizeof(value), 0);
            if (hr == S_OK) return;
            check(hr, "query");
            if (milliseconds(start) > 2000) throw std::runtime_error("GPU query deadline");
            std::this_thread::yield();
        }
    }
    Timings run(const std::vector<uint32_t>& in, std::vector<uint32_t>& out, UINT rounds, UINT seed) {
        const auto start = Clock::now();
        const UINT count = static_cast<UINT>(in.size()); const UINT params[] = {count, rounds, seed, 0};
        context->UpdateSubresource(input.Get(), 0, nullptr, in.data(), 0, 0);
        context->UpdateSubresource(parameters.Get(), 0, nullptr, params, 0, 0);
        context->Begin(disjoint.Get()); context->End(begin.Get());
        context->Dispatch((count + 255) / 256, 1, 1);
        context->End(end.Get()); context->End(disjoint.Get());
        context->CopyResource(staging.Get(), output.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        check(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped), "readback");
        std::memcpy(out.data(), mapped.pData, in.size() * sizeof(uint32_t)); context->Unmap(staging.Get(), 0);
        const auto whole_ms = milliseconds(start);
        D3D11_QUERY_DATA_TIMESTAMP_DISJOINT timing{}; UINT64 a = 0, b = 0;
        query(disjoint.Get(), timing); query(begin.Get(), a); query(end.Get(), b);
        if (timing.Disjoint || !timing.Frequency || b < a) throw std::runtime_error("invalid GPU clock sample");
        return {whole_ms, double(b - a) * 1000.0 / double(timing.Frequency), milliseconds(start)};
    }
};
int wmain(int argc, wchar_t** argv) {
    try {
        if (argc != 2) throw std::runtime_error("expected shader source path");
        std::cout << std::setprecision(9);
        Gpu gpu(argv[1]);
        const size_t workers = std::max(size_t(1), std::min(size_t(8), size_t(std::thread::hardware_concurrency())));
        const auto pool_start = Clock::now(); Pool pool(workers); const double pool_ms = milliseconds(pool_start);
        for (UINT count : {4096U, 65536U, 1048576U}) {
            const double buffers_ms = gpu.allocate(count);
            std::vector<uint32_t> input(count), scalar(count), simd(count), parallel(count), output(count);
            for (UINT i = 0; i < count; ++i) input[i] = i * 2654435761U + 17U;
            for (UINT rounds : {0U, 1U, 64U}) {
                for (int repeat = -1; repeat < 9; ++repeat) {
                    const UINT seed = 0x9e3779b9U + static_cast<UINT>(repeat + 1);
                    double scalar_ms = 0, simd_ms = 0, pool_batch_ms = 0; Gpu::Timings gpu_ms{};
                    const auto run_cpu = [&] {
                        auto t = Clock::now(); cpu(input.data(), scalar.data(), 0, count, rounds, seed); scalar_ms = milliseconds(t);
                        t = Clock::now(); cpu_optimized(input.data(), simd.data(), 0, count, rounds, seed); simd_ms = milliseconds(t);
                        t = Clock::now(); pool.run(input, parallel, rounds, seed); pool_batch_ms = milliseconds(t);
                    };
                    if (repeat % 2 == 0) { gpu_ms = gpu.run(input, output, rounds, seed); run_cpu(); }
                    else { run_cpu(); gpu_ms = gpu.run(input, output, rounds, seed); }
                    for (UINT i = 0; i < count; ++i) {
                        if (output[i] != scalar[i] || parallel[i] != scalar[i] || simd[i] != scalar[i]) {
                            throw std::runtime_error("FIRST DIVERGENCE count=" + std::to_string(count) +
                                " rounds=" + std::to_string(rounds) + " repeat=" + std::to_string(repeat) +
                                " index=" + std::to_string(i) + " input=" + std::to_string(input[i]) +
                                " scalar=" + std::to_string(scalar[i]) + " pool=" + std::to_string(parallel[i]) +
                                " gpu=" + std::to_string(output[i]) + " simd=" + std::to_string(simd[i]));
                        }
                    }
                    std::cout << "{\"kind\":\"sample\",\"count\":" << count << ",\"rounds\":" << rounds << ",\"repeat\":" << repeat
                        << ",\"seed\":" << seed << ",\"workers\":" << workers << ",\"pool_setup_ms\":" << pool_ms << ",\"buffers_ms\":" << buffers_ms
                        << ",\"scalar_ms\":" << scalar_ms << ",\"simd_ms\":" << simd_ms << ",\"pool_ms\":" << pool_batch_ms << ",\"gpu_whole_ms\":" << gpu_ms.whole
                        << ",\"gpu_kernel_ms\":" << gpu_ms.kernel << ",\"gpu_wrapper_ms\":" << gpu_ms.wrapper
                        << ",\"logical_array_bytes_each_direction\":" << count * 4 << ",\"parameter_upload_bytes\":16"
                        << ",\"parity\":true,\"golden_indices\":[0,1,31," << count - 1 << "],\"golden_values\":["
                        << output[0] << ',' << output[1] << ',' << output[31] << ',' << output[count - 1] << "]}\n";
                }
            }
        }
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
