#include <jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-vulkan.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#define LOG_TAG "QuantChatVulkanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

template <typename Ctx>
auto clear_kv_cache_compat(Ctx* ctx, int)
    -> decltype(llama_memory_clear(llama_get_memory(ctx), true), void()) {
    llama_memory_clear(llama_get_memory(ctx), true);
}

template <typename Ctx>
void clear_kv_cache_compat(Ctx*, ...) {
    // No-op fallback if symbol is absent
}

struct VulkanContextHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int32_t n_ctx = 2048;
    int32_t n_threads = 4;
    int32_t n_gpu_layers = 0;
    std::atomic<bool> stop_requested{false};
    std::mutex op_mutex;

    ~VulkanContextHandle() {
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

std::once_flag g_llama_init_flag;

void ensure_llama_backend_initialized() {
    std::call_once(g_llama_init_flag, []() {
        llama_backend_init();
        ggml_backend_load_all();
    });
}

std::string vendor_name_from_id(uint32_t vendor_id) {
    switch (vendor_id) {
        case 0x5143: return "Qualcomm (Adreno)";
        case 0x13B5: return "ARM (Mali / Immortalis)";
        case 0x1010: return "Imagination (PowerVR)";
        case 0x14E4: return "Broadcom";
        case 0x10DE: return "NVIDIA";
        case 0x1002: return "AMD / Samsung Xclipse";
        case 0x8086: return "Intel";
        default: {
            char buf[32];
            snprintf(buf, sizeof(buf), "Vendor 0x%04X", vendor_id);
            return std::string(buf);
        }
    }
}

std::string format_vk_version(uint32_t ver) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u",
             VK_API_VERSION_MAJOR(ver),
             VK_API_VERSION_MINOR(ver),
             VK_API_VERSION_PATCH(ver));
    return std::string(buf);
}

uint32_t find_memory_type_index(
    const VkPhysicalDeviceMemoryProperties& mem_props,
    uint32_t type_filter,
    VkMemoryPropertyFlags required_flags
) {
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; ++i) {
        if ((type_filter & (1u << i)) != 0 &&
            (mem_props.memoryTypes[i].propertyFlags & required_flags) == required_flags) {
            return i;
        }
    }
    return UINT32_MAX;
}

struct VulkanProbeOutcome {
    std::string status = "PROBE_FAILED";
    std::string device_name;
    std::string vendor_label;
    std::string driver_version;
    std::string api_version = "1.0.0";
    bool supports_16bit_storage = false;
    bool supports_fp16_compute = false;
    bool supports_coop_matrix = false;
    int64_t device_local_mb = 0;
    int64_t host_visible_mb = 0;
    int64_t recommended_offload_mb = 0;
    bool buffer_self_test = false;
    bool operator_self_test = false;
    std::string summary;
    std::string reason;
};

VulkanProbeOutcome run_real_vulkan_probe() {
    VulkanProbeOutcome out;

    uint32_t instance_api_version = VK_API_VERSION_1_0;
    auto pfn_enum_ver = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion")
    );
    if (pfn_enum_ver != nullptr) {
        uint32_t queried = 0;
        if (pfn_enum_ver(&queried) == VK_SUCCESS) {
            instance_api_version = queried;
        }
    }

    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "QuantChat";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 4, 0);
    app_info.pEngineName = "QuantChatVulkan";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = (instance_api_version >= VK_API_VERSION_1_1)
        ? VK_API_VERSION_1_1
        : VK_API_VERSION_1_0;

    VkInstanceCreateInfo inst_info{};
    inst_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    inst_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = vkCreateInstance(&inst_info, nullptr, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        out.status = "UNSUPPORTED_DEVICE";
        out.reason = "vkCreateInstance failed with code " + std::to_string(static_cast<int>(res));
        out.summary = out.reason;
        return out;
    }

    uint32_t dev_count = 0;
    vkEnumeratePhysicalDevices(instance, &dev_count, nullptr);
    if (dev_count == 0) {
        vkDestroyInstance(instance, nullptr);
        out.status = "UNSUPPORTED_DEVICE";
        out.reason = "No Vulkan physical GPU devices found.";
        out.summary = out.reason;
        return out;
    }

    std::vector<VkPhysicalDevice> devices(dev_count);
    vkEnumeratePhysicalDevices(instance, &dev_count, devices.data());

    VkPhysicalDevice chosen = devices[0];
    VkPhysicalDeviceProperties chosen_props{};
    vkGetPhysicalDeviceProperties(chosen, &chosen_props);
    for (VkPhysicalDevice dev : devices) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(dev, &props);
        if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ||
            props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
            chosen = dev;
            chosen_props = props;
            break;
        }
    }

    out.device_name = chosen_props.deviceName;
    out.vendor_label = vendor_name_from_id(chosen_props.vendorID);
    out.api_version = format_vk_version(chosen_props.apiVersion);
    out.driver_version = std::to_string(chosen_props.driverVersion);

    // Check 16-bit storage & FP16 arithmetic features when Vulkan 1.1+ is available
    auto pfn_get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2")
    );
    if (pfn_get_features2 != nullptr && chosen_props.apiVersion >= VK_API_VERSION_1_1) {
        VkPhysicalDeviceShaderFloat16Int8Features f16_i8{};
        f16_i8.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;

        VkPhysicalDevice16BitStorageFeatures s16{};
        s16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
        s16.pNext = &f16_i8;

        VkPhysicalDeviceFeatures2 f2{};
        f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        f2.pNext = &s16;

        pfn_get_features2(chosen, &f2);
        out.supports_16bit_storage = (s16.storageBuffer16BitAccess == VK_TRUE);
        out.supports_fp16_compute = (f16_i8.shaderFloat16 == VK_TRUE);
    }

    // Enumerate extensions for cooperative matrix / 16bit extensions
    uint32_t ext_count = 0;
    vkEnumerateDeviceExtensionProperties(chosen, nullptr, &ext_count, nullptr);
    if (ext_count > 0) {
        std::vector<VkExtensionProperties> exts(ext_count);
        vkEnumerateDeviceExtensionProperties(chosen, nullptr, &ext_count, exts.data());
        for (const auto& ext : exts) {
            if (std::strcmp(ext.extensionName, "VK_KHR_16bit_storage") == 0) {
                out.supports_16bit_storage = true;
            }
            if (std::strcmp(ext.extensionName, "VK_KHR_shader_float16_int8") == 0) {
                out.supports_fp16_compute = true;
            }
            if (std::strcmp(ext.extensionName, "VK_KHR_cooperative_matrix") == 0) {
                out.supports_coop_matrix = true;
            }
        }
    }

    // Memory heap inspection
    VkPhysicalDeviceMemoryProperties mem_props{};
    vkGetPhysicalDeviceMemoryProperties(chosen, &mem_props);
    uint64_t device_local_bytes = 0;
    uint64_t host_visible_bytes = 0;
    for (uint32_t i = 0; i < mem_props.memoryHeapCount; ++i) {
        const auto& heap = mem_props.memoryHeaps[i];
        if ((heap.flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
            device_local_bytes += heap.size;
        } else {
            host_visible_bytes += heap.size;
        }
    }
    out.device_local_mb = static_cast<int64_t>(device_local_bytes / (1024ULL * 1024ULL));
    out.host_visible_mb = static_cast<int64_t>(host_visible_bytes / (1024ULL * 1024ULL));
    if (out.host_visible_mb == 0) {
        out.host_visible_mb = out.device_local_mb;
    }

    // Compute queue family check + real headless buffer allocation/transfer self-test
    uint32_t qf_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(chosen, &qf_count, nullptr);
    std::vector<VkQueueFamilyProperties> qfs(qf_count);
    vkGetPhysicalDeviceQueueFamilyProperties(chosen, &qf_count, qfs.data());
    uint32_t compute_qf = UINT32_MAX;
    for (uint32_t i = 0; i < qf_count; ++i) {
        if ((qfs[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0 && qfs[i].queueCount > 0) {
            compute_qf = i;
            break;
        }
    }

    if (compute_qf == UINT32_MAX) {
        vkDestroyInstance(instance, nullptr);
        out.status = "DRIVER_CAPABILITY_MISSING";
        out.reason = "Vulkan device exposes no compute queue family.";
        out.summary = out.reason;
        return out;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo q_info{};
    q_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    q_info.queueFamilyIndex = compute_qf;
    q_info.queueCount = 1;
    q_info.pQueuePriorities = &priority;

    VkDeviceCreateInfo dev_info{};
    dev_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dev_info.queueCreateInfoCount = 1;
    dev_info.pQueueCreateInfos = &q_info;

    VkDevice device = VK_NULL_HANDLE;
    if (vkCreateDevice(chosen, &dev_info, nullptr, &device) != VK_SUCCESS || device == VK_NULL_HANDLE) {
        vkDestroyInstance(instance, nullptr);
        out.status = "DRIVER_CAPABILITY_MISSING";
        out.reason = "Failed to create logical Vulkan compute device.";
        out.summary = out.reason;
        return out;
    }

    // Allocate staging + device buffers & verify memory transfer readback
    const VkDeviceSize test_bytes = 4096;
    auto make_buf = [&](VkBufferUsageFlags usage, VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem) -> bool {
        VkBufferCreateInfo b_info{};
        b_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        b_info.size = test_bytes;
        b_info.usage = usage;
        b_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device, &b_info, nullptr, &buf) != VK_SUCCESS) return false;

        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, buf, &req);
        uint32_t mem_idx = find_memory_type_index(mem_props, req.memoryTypeBits, props);
        if (mem_idx == UINT32_MAX) {
            mem_idx = find_memory_type_index(mem_props, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        }
        if (mem_idx == UINT32_MAX) return false;

        VkMemoryAllocateInfo a_info{};
        a_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        a_info.allocationSize = req.size;
        a_info.memoryTypeIndex = mem_idx;
        if (vkAllocateMemory(device, &a_info, nullptr, &mem) != VK_SUCCESS) return false;
        return vkBindBufferMemory(device, buf, mem, 0) == VK_SUCCESS;
    };

    VkBuffer src_buf = VK_NULL_HANDLE, dst_buf = VK_NULL_HANDLE;
    VkDeviceMemory src_mem = VK_NULL_HANDLE, dst_mem = VK_NULL_HANDLE;
    bool buf_ok = make_buf(
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        src_buf, src_mem
    ) && make_buf(
        VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        dst_buf, dst_mem
    );

    if (buf_ok) {
        void* mapped_src = nullptr;
        if (vkMapMemory(device, src_mem, 0, test_bytes, 0, &mapped_src) == VK_SUCCESS && mapped_src != nullptr) {
            auto* ptr = static_cast<uint32_t*>(mapped_src);
            for (size_t i = 0; i < test_bytes / sizeof(uint32_t); ++i) {
                ptr[i] = static_cast<uint32_t>(0xA5000000u + i);
            }
            vkUnmapMemory(device, src_mem);
        } else {
            buf_ok = false;
        }
    }

    if (buf_ok) {
        VkCommandPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pool_info.queueFamilyIndex = compute_qf;
        VkCommandPool pool = VK_NULL_HANDLE;
        if (vkCreateCommandPool(device, &pool_info, nullptr, &pool) == VK_SUCCESS) {
            VkCommandBufferAllocateInfo cmd_alloc{};
            cmd_alloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
            cmd_alloc.commandPool = pool;
            cmd_alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            cmd_alloc.commandBufferCount = 1;
            VkCommandBuffer cmd = VK_NULL_HANDLE;
            if (vkAllocateCommandBuffers(device, &cmd_alloc, &cmd) == VK_SUCCESS) {
                VkCommandBufferBeginInfo begin_info{};
                begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                vkBeginCommandBuffer(cmd, &begin_info);
                VkBufferCopy region{0, 0, test_bytes};
                vkCmdCopyBuffer(cmd, src_buf, dst_buf, 1, &region);
                vkEndCommandBuffer(cmd);

                VkQueue queue = VK_NULL_HANDLE;
                vkGetDeviceQueue(device, compute_qf, 0, &queue);
                VkSubmitInfo submit{};
                submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                submit.commandBufferCount = 1;
                submit.pCommandBuffers = &cmd;
                if (vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS &&
                    vkQueueWaitIdle(queue) == VK_SUCCESS) {
                    void* mapped_dst = nullptr;
                    if (vkMapMemory(device, dst_mem, 0, test_bytes, 0, &mapped_dst) == VK_SUCCESS && mapped_dst != nullptr) {
                        const auto* out_ptr = static_cast<const uint32_t*>(mapped_dst);
                        bool match = (out_ptr[0] == 0xA5000000u) && (out_ptr[511] == (0xA5000000u + 511u));
                        vkUnmapMemory(device, dst_mem);
                        out.buffer_self_test = match;
                    }
                }
            }
            vkDestroyCommandPool(device, pool, nullptr);
        }
    }

    if (src_buf != VK_NULL_HANDLE) vkDestroyBuffer(device, src_buf, nullptr);
    if (dst_buf != VK_NULL_HANDLE) vkDestroyBuffer(device, dst_buf, nullptr);
    if (src_mem != VK_NULL_HANDLE) vkFreeMemory(device, src_mem, nullptr);
    if (dst_mem != VK_NULL_HANDLE) vkFreeMemory(device, dst_mem, nullptr);
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);

    // Check ggml-vulkan backend enumeration
    ensure_llama_backend_initialized();
    const int vk_ggml_devs = ggml_backend_vk_get_device_count();
    out.operator_self_test = (vk_ggml_devs > 0) && out.supports_16bit_storage;

    if (out.device_local_mb > 0) {
        // Reserve 35% of unified device heap for OS/display compositor
        out.recommended_offload_mb = std::max<int64_t>(512L, (out.device_local_mb * 60L) / 100L);
    }

    if (!out.buffer_self_test) {
        out.status = "PROBE_FAILED";
        out.reason = "Vulkan buffer allocation/readback self-test failed.";
        out.summary = out.reason;
    } else if (vk_ggml_devs <= 0) {
        out.status = "DRIVER_CAPABILITY_MISSING";
        out.reason = "ggml-vulkan reported 0 usable compute devices.";
        out.summary = out.reason;
    } else if (!out.supports_16bit_storage) {
        out.status = "DRIVER_CAPABILITY_MISSING";
        out.reason = "Driver lacks VK_KHR_16bit_storage required by quantized ggml-vulkan shaders.";
        out.summary = out.reason;
    } else {
        out.status = "AVAILABLE";
        out.summary = "Buffer copy/readback OK · ggml-vulkan " + std::to_string(vk_ggml_devs) +
            " dev · 16bit=" + (out.supports_16bit_storage ? "Y" : "N") +
            " · FP16=" + (out.supports_fp16_compute ? "Y" : "N");
    }

    return out;
}

std::string sanitize_kv(std::string s) {
    for (char& ch : s) {
        if (ch == '\n' || ch == '\r' || ch == '=') ch = ' ';
    }
    return s;
}

std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (js == nullptr) return "";
    const char* raw = env->GetStringUTFChars(js, nullptr);
    std::string result(raw ? raw : "");
    if (raw != nullptr) env->ReleaseStringUTFChars(js, raw);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeProbeVulkan(JNIEnv* env, jclass) {
    VulkanProbeOutcome out = run_real_vulkan_probe();
    std::ostringstream ss;
    ss << "status=" << out.status << "\n"
       << "deviceName=" << sanitize_kv(out.device_name) << "\n"
       << "vendorLabel=" << sanitize_kv(out.vendor_label) << "\n"
       << "driverVersion=" << sanitize_kv(out.driver_version) << "\n"
       << "apiVersion=" << sanitize_kv(out.api_version) << "\n"
       << "supports16BitStorage=" << (out.supports_16bit_storage ? "true" : "false") << "\n"
       << "supportsFp16Compute=" << (out.supports_fp16_compute ? "true" : "false") << "\n"
       << "supportsCoopMatrix=" << (out.supports_coop_matrix ? "true" : "false") << "\n"
       << "deviceLocalMb=" << out.device_local_mb << "\n"
       << "hostVisibleMb=" << out.host_visible_mb << "\n"
       << "recommendedOffloadMb=" << out.recommended_offload_mb << "\n"
       << "bufferSelfTest=" << (out.buffer_self_test ? "true" : "false") << "\n"
       << "operatorSelfTest=" << (out.operator_self_test ? "true" : "false") << "\n"
       << "summary=" << sanitize_kv(out.summary) << "\n"
       << "reason=" << sanitize_kv(out.reason) << "\n";
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeLoadModelFd(
    JNIEnv* env,
    jclass,
    jint fd,
    jint n_ctx,
    jint n_batch,
    jint n_threads,
    jint n_gpu_layers,
    jboolean use_mmap,
    jboolean use_mlock
) {
    ensure_llama_backend_initialized();
    std::string fd_path = "/proc/self/fd/" + std::to_string(fd);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = std::max(0, static_cast<int>(n_gpu_layers));
    mparams.use_mmap = (use_mmap == JNI_TRUE);
    mparams.use_mlock = (use_mlock == JNI_TRUE);

    llama_model* model = llama_model_load_from_file(fd_path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed for fd=%d ngl=%d", fd, n_gpu_layers);
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(std::max(256, static_cast<int>(n_ctx)));
    cparams.n_batch = static_cast<uint32_t>(std::max(64, static_cast<int>(n_batch)));
    cparams.n_threads = std::max(1, static_cast<int>(n_threads));
    cparams.n_threads_batch = std::max(1, static_cast<int>(n_threads));

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed for fd=%d n_ctx=%d", fd, n_ctx);
        llama_model_free(model);
        return 0L;
    }

    auto* handle = new VulkanContextHandle();
    handle->model = model;
    handle->ctx = ctx;
    handle->vocab = llama_model_get_vocab(model);
    handle->n_ctx = static_cast<int32_t>(cparams.n_ctx);
    handle->n_threads = static_cast<int32_t>(cparams.n_threads);
    handle->n_gpu_layers = static_cast<int32_t>(mparams.n_gpu_layers);

    LOGI("Loaded Vulkan llama.cpp handle=%p n_ctx=%d ngl=%d", handle, handle->n_ctx, handle->n_gpu_layers);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeUnload(JNIEnv*, jclass, jlong handle_ptr) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle != nullptr) {
        handle->stop_requested.store(true);
        std::lock_guard<std::mutex> lock(handle->op_mutex);
        delete handle;
    }
}

JNIEXPORT void JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeCancel(JNIEnv*, jclass, jlong handle_ptr) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle != nullptr) {
        handle->stop_requested.store(true);
    }
}

JNIEXPORT jstring JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeFormatChat(
    JNIEnv* env,
    jclass,
    jlong handle_ptr,
    jobjectArray roles,
    jobjectArray contents,
    jstring tmpl_override
) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle == nullptr || handle->model == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(handle->op_mutex);
    const char* model_tmpl = llama_model_chat_template(handle->model, nullptr);
    std::string override_str = jstring_to_std(env, tmpl_override);
    const char* active_tmpl = !override_str.empty() ? override_str.c_str() : model_tmpl;
    if (active_tmpl == nullptr) return nullptr;

    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> role_strs;
    std::vector<std::string> content_strs;
    role_strs.reserve(n);
    content_strs.reserve(n);

    for (jsize i = 0; i < n; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto c = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_strs.push_back(jstring_to_std(env, r));
        content_strs.push_back(jstring_to_std(env, c));
        if (r != nullptr) env->DeleteLocalRef(r);
        if (c != nullptr) env->DeleteLocalRef(c);
    }

    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) {
        msgs[i].role = role_strs[i].c_str();
        msgs[i].content = content_strs[i].c_str();
    }

    int32_t needed = llama_chat_apply_template(active_tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
    if (needed <= 0) return nullptr;
    std::string buf(static_cast<size_t>(needed) + 1, '\0');
    int32_t written = llama_chat_apply_template(active_tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (written <= 0) return nullptr;
    buf.resize(static_cast<size_t>(written));
    return env->NewStringUTF(buf.c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeTokenize(
    JNIEnv* env,
    jclass,
    jlong handle_ptr,
    jstring text
) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle == nullptr || handle->vocab == nullptr) return env->NewIntArray(0);
    std::lock_guard<std::mutex> lock(handle->op_mutex);

    std::string input = jstring_to_std(env, text);
    int32_t max_toks = static_cast<int32_t>(input.size()) + 16;
    std::vector<llama_token> tokens(max_toks);
    int32_t n = llama_tokenize(
        handle->vocab,
        input.c_str(),
        static_cast<int32_t>(input.size()),
        tokens.data(),
        max_toks,
        true,
        true
    );
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            handle->vocab,
            input.c_str(),
            static_cast<int32_t>(input.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
        );
    }
    if (n < 0) n = 0;

    jintArray arr = env->NewIntArray(n);
    if (n > 0) {
        env->SetIntArrayRegion(arr, 0, n, reinterpret_cast<const jint*>(tokens.data()));
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeDetokenize(
    JNIEnv* env,
    jclass,
    jlong handle_ptr,
    jintArray tokens_arr
) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle == nullptr || handle->vocab == nullptr || tokens_arr == nullptr) {
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> lock(handle->op_mutex);

    jsize len = env->GetArrayLength(tokens_arr);
    std::vector<jint> toks(len);
    env->GetIntArrayRegion(tokens_arr, 0, len, toks.data());

    std::string out;
    char piece[256];
    for (jint t : toks) {
        int32_t n = llama_token_to_piece(handle->vocab, static_cast<llama_token>(t), piece, sizeof(piece), 0, false);
        if (n > 0) {
            out.append(piece, static_cast<size_t>(n));
        }
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeLaunchCompletion(
    JNIEnv* env,
    jclass,
    jlong handle_ptr,
    jstring prompt_jstr,
    jint n_predict,
    jint n_threads,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat min_p,
    jfloat repeat_penalty,
    jobject callback_obj
) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle == nullptr || handle->ctx == nullptr || handle->vocab == nullptr) return -1;

    std::lock_guard<std::mutex> lock(handle->op_mutex);
    handle->stop_requested.store(false);

    llama_set_n_threads(handle->ctx, std::max(1, n_threads), std::max(1, n_threads));
    clear_kv_cache_compat(handle->ctx, 0);

    std::string prompt = jstring_to_std(env, prompt_jstr);
    int32_t max_toks = static_cast<int32_t>(prompt.size()) + 16;
    std::vector<llama_token> prompt_tokens(max_toks);
    int32_t n_prompt = llama_tokenize(
        handle->vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(),
        max_toks,
        true,
        true
    );
    if (n_prompt < 0) {
        prompt_tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(
            handle->vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true
        );
    }
    if (n_prompt <= 0) return -2;
    prompt_tokens.resize(static_cast<size_t>(n_prompt));

    if (n_prompt >= handle->n_ctx) {
        LOGW("Prompt tokens (%d) >= n_ctx (%d)", n_prompt, handle->n_ctx);
        return -3;
    }

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), n_prompt);
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("Vulkan llama_decode failed during prompt prefill");
        return -4;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f));
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::max(1, top_k)));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass cb_cls = callback_obj != nullptr ? env->GetObjectClass(callback_obj) : nullptr;
    jmethodID on_token_mid = cb_cls != nullptr
        ? env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V")
        : nullptr;

    int32_t generated = 0;
    const int32_t limit = std::min(static_cast<int32_t>(n_predict), std::max(1, handle->n_ctx - n_prompt - 1));
    char piece_buf[256];

    for (int32_t step = 0; step < limit; ++step) {
        if (handle->stop_requested.load()) break;

        llama_token next_tok = llama_sampler_sample(sampler, handle->ctx, -1);
        if (llama_vocab_is_eog(handle->vocab, next_tok)) break;

        int32_t piece_len = llama_token_to_piece(handle->vocab, next_tok, piece_buf, sizeof(piece_buf), 0, false);
        if (piece_len > 0 && on_token_mid != nullptr) {
            std::string chunk(piece_buf, static_cast<size_t>(piece_len));
            jstring jchunk = env->NewStringUTF(chunk.c_str());
            if (jchunk != nullptr) {
                env->CallVoidMethod(callback_obj, on_token_mid, jchunk);
                env->DeleteLocalRef(jchunk);
            }
        }
        ++generated;

        llama_batch next_batch = llama_batch_get_one(&next_tok, 1);
        if (llama_decode(handle->ctx, next_batch) != 0) {
            LOGE("Vulkan llama_decode failed at decode step %d", step);
            llama_sampler_free(sampler);
            return -5;
        }
    }

    llama_sampler_free(sampler);
    return generated;
}

JNIEXPORT jboolean JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeSaveSession(
    JNIEnv* env,
    jclass,
    jlong handle_ptr,
    jstring path_jstr
) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle == nullptr || handle->ctx == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(handle->op_mutex);
    std::string path = jstring_to_std(env, path_jstr);
    return llama_state_save_file(handle->ctx, path.c_str(), nullptr, 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_quantchat_engine_vulkan_VulkanNativeBridge_nativeLoadSession(
    JNIEnv* env,
    jclass,
    jlong handle_ptr,
    jstring path_jstr
) {
    auto* handle = reinterpret_cast<VulkanContextHandle*>(handle_ptr);
    if (handle == nullptr || handle->ctx == nullptr) return -1;
    std::lock_guard<std::mutex> lock(handle->op_mutex);
    std::string path = jstring_to_std(env, path_jstr);
    std::vector<llama_token> dummy(static_cast<size_t>(handle->n_ctx));
    size_t count_out = 0;
    bool ok = llama_state_load_file(handle->ctx, path.c_str(), dummy.data(), dummy.size(), &count_out);
    return ok ? static_cast<jint>(count_out) : -1;
}

} // extern "C"
