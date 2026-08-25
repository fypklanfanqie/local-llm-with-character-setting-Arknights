// MNN 后端 Android JNI 桥接（移植自 MNN `apps/Android/MnnLlmChat` 的 llm_session + llm_mnn_jni）。
// 编译产物 libmnn_jni.so，由 MnnBridge 加载（依赖 c++_shared -> libMNN.so -> 本库）。
// CMake 见 cpp/CMakeLists.txt 的 mnn_jni target。
//
// 设计要点（stepping 算法，对齐 MnnLlmChat llm_session.cpp::ResponseWithHistory）：
//  1) 模型加载：`Llm::createLLM(config.json)` -> `set_config(文档对齐的运行期配置)` -> `load()`。
//     backend_type 取自 MnnBackend.MnnMode（cpu/opencl/qnn），qnn 需 libMNN.so 含 BUILD_QNN=ON
//     且运行时加载 libQnnHtp*.so，否则 load() 失败、nativeCreate 返回 0、BackendManager 回退。
//     set_config 走 config_.merge() 且在 load() 前 -> 覆盖模型自带 config.json 与任何旧值，于
//     load()->initRuntime()->setRuntimeHint() 生效。键值依据 MNN 官方文档
//     https://mnn-docs.readthedocs.io/en/latest/transformers/llm.html 「运行时配置」一节，详见
//     nativeCreate 内注释。
//  2) 聊天模板：MNN 按各模型自带 llm_config.json/tokenizer 应用（Qwen=ChatML，Llama/Gemma/Phi
//     各异），故 nativeGenerateStream 接收消息列表 JSON 而非预格式化 ChatML 串，调用
//     `Llm::response(ChatMessages, &os, "<eop>", 0)` 由 MNN 套模板后 prefill。
//  3) 流式 + 真·中断（stepping）：prefill 用 `response(history, &os, "<eop>", 0)`（max_new_tokens=0
//     仅 prefill，<eop> 为停止串），随后循环 `generate(1)` 逐 token decode。每步之间轮询
//     shouldAbort()——命中即 break，**1 个 token 内停止**（取代旧版"let-finish 跑完剩余 token"）。
//     MNN 把 response() 传入的 ostream 存入 LlmContext，generate(1) 继续向其写 token，故流式回调
//     不断。UTF-8 字符边界切分由 Utf8StreamProcessor 处理（CJK/emoji 残缺多字节序列）。
//  4) <eop> / EOS 检测：本 libMNN.so（对齐 MNN 源码 ArGeneration::generate）仅在命中 EOS token
//     时把 end_with("<eop>") 写入流（is_stop 分支），每步 generate(1) 末尾置 status=
//     MAX_TOKENS_FINISHED（无论是否 EOS）。故 <eop> 入流（pending_eop）即真·EOS -> finalizePendingEop
//     置 generate_text_end 退出循环；CHECK_LLM_RUNNING 不拦 MAX_TOKENS_FINISHED、is_stop 防 EOS 后再
//     decode，故无需复位 status。（MnnLlmChat 的 resolveAndroidSteppingEop 是为「每步吐 <eop>」的另一
//     预编译运行时写的怪癖处理，本构建无该怪癖，套用反而会清掉真·EOS 导致跑到 max_tokens。）
//  5) KV 缓存对齐（多轮前缀复用的关键）：正常结束时把 assistant 回写 history 后
//     `syncPromptCache(history)`——MNN 用此对齐内部 mCachedPromptText（含后处理后的回复），
//     下一轮 response(full_history) 命中前缀 -> reuse_kv 仅 prefill 新增 token。中断时
//     `eraseHistory(kv_before_decode, 0)` 回滚到 prefill 后状态，使下一轮前缀仍命中缓存
//     （否则被取消的半句会污染前缀，导致全量重 prefill）。
//  6) 采样参数：temperature/top_p/repetition_penalty 在 load() 前 set_config（采样器在 load 内
//     一次性构建）。nativeGenerateStream 内 best-effort 再 set_config 一次（部分构建按 response
//     重建采样器则生效，否则 no-op）。temperature 改值由 Kotlin BackendManager 纳入重载指纹触发重载。
//  7) v2 生成契约（Task 1）：nativeGenerateStream 末尾追加 decode_step_tokens（clamp 到 [1,4]，
//     缺省/非法值 -> 1，等价 v1 逐 token 行为）；prefill/decode/finalize 抽成 GenerationSession
//     三阶段状态机（行为与 v1 完全一致：StreamBatcher / UTF-8 / syncPromptCache / eraseHistory /
//     <eop> 语义保留）；摘要 v2 新增 decodeStepTokens / thinkingConfigAccepted（set_config 返回值）/
//     reasoningEndUs（输出流旁路扫描 </think>，us 相对生成起点）/ firstBodyDeltaUs（</think> 之后
//     首个回调）/ errorCode；nativeGetRuntimeInfo capabilities 追加 "summary_v2"（Kotlin
//     MnnBridge.hasSummaryV2Capability 查询，Task 8 发布门禁使用；v1 兼容路径继续可用）。

#include <jni.h>
#include <string>
#include <vector>
#include <utility>
#include <ostream>
#include <streambuf>
#include <functional>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <cstdint>
#include <chrono>
#include <sstream>
#include <android/log.h>

#include "llm/llm.hpp"

#define MNN_JNI_TAG "MnnJni"
#define MNN_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  MNN_JNI_TAG, __VA_ARGS__)
#define MNN_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  MNN_JNI_TAG, __VA_ARGS__)
#define MNN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MNN_JNI_TAG, __VA_ARGS__)

// ===== 运行时溯源编译定义（由 CMakeLists 注入；守卫以防未注入时的裸编译）=====
// 与 Kotlin MnnBridge.MnnRuntimeInfo 对齐：abiVersion 不符即 JNI 契约不兼容。
#ifndef CHAT_MNN_COMMIT
#define CHAT_MNN_COMMIT "unknown"
#endif
#ifndef CHAT_MNN_JNI_ABI
#define CHAT_MNN_JNI_ABI 0
#endif
#ifndef CHAT_MNN_BUILD_ID
#define CHAT_MNN_BUILD_ID "unknown"
#endif

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;
using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;

// ===== JNI 类/方法缓存（首次调用 ensure_jni_cache 取 MnnBridge 类与 nativeCallback/shouldAbort 方法 ID）=====
static jclass    g_bridge_cls       = nullptr;
static jmethodID g_callback_mid     = nullptr;
static jmethodID g_should_abort_mid = nullptr;

// 最近一次 nativeCreate 的加载失败原因（供 nativeGetLastError 回传 Kotlin，定位「所有后端加载失败」根因）。
// nativeCreate 由 MnnBackend.mnnMutex 串行（三类 MNN 后端共享），且 nativeGetLastError 紧随 nativeCreate
// 失败返回后立即调用，无并发覆盖。load 成功时清空。
static std::string g_last_load_error;

static void ensure_jni_cache(JNIEnv *env) {
    if (g_bridge_cls) return;
    jclass local = env->FindClass("com/chatbyyourside/llm/backend/MnnBridge");
    if (!local) { MNN_LOGE("FindClass MnnBridge 失败"); return; }
    g_bridge_cls = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    g_callback_mid = env->GetStaticMethodID(g_bridge_cls, "nativeCallback", "([BI)V");
    g_should_abort_mid = env->GetStaticMethodID(g_bridge_cls, "shouldAbort", "()Z");
    if (!g_callback_mid || !g_should_abort_mid) {
        MNN_LOGE("取 MnnBridge 方法 ID 失败 cb=%p abort=%p", g_callback_mid, g_should_abort_mid);
    }
}

// 把一段字节推给 Java（ByteArray，Kotlin 侧 String(bytes, UTF_8) 解码）并携带当前已生成 token 数。
// gen_len 为真实 token 数（LlmContext::gen_seq_len / stepping 步数），批处理后回调次数≠token 数，
// 供 Kotlin 浮窗实时 tps 用（MnnBackend 不再用回调次数冒充 token 数）。
static void push_bytes(JNIEnv *env, const char *data, int len, jint gen_len) {
    if (len <= 0 || !g_bridge_cls || !g_callback_mid) return;
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, len, (const jbyte *)data);
    env->CallStaticVoidMethod(g_bridge_cls, g_callback_mid, arr, gen_len);
    env->DeleteLocalRef(arr);
    // Kotlin 回调若抛异常（如协程取消/上层断言），清掉 pending 异常避免后续 JNI 调用崩
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// 轮询 Kotlin 侧 MnnBridge.abort 静态标志（stepping 循环每步检测，实现真·中断）。
static bool should_abort(JNIEnv *env) {
    if (!g_bridge_cls || !g_should_abort_mid) return false;
    jboolean v = env->CallStaticBooleanMethod(g_bridge_cls, g_should_abort_mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return v == JNI_TRUE;
}

// ===== UTF-8 流式处理（移植自 MnnLlmChat utf8_stream_processor.hpp / llm_stream_buffer.hpp）=====

// streambuf：MNN response()/generate(1) 逐 token 写入 -> 回调 (char*, len)。
class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char *str, size_t len)>;
    explicit LlmStreamBuffer(CallBack callback) : callback_(std::move(callback)) {}
protected:
    std::streamsize xsputn(const char *s, std::streamsize n) override {
        if (callback_) callback_(s, (size_t)n);
        return n;
    }
    int_type overflow(int_type c) override {
        if (c != traits_type::eof() && callback_) {
            char ch = (char)c;
            callback_(&ch, 1);
        }
        return c;
    }
private:
    CallBack callback_ = nullptr;
};

// 缓冲字节，仅向下游吐出完整 UTF-8 字符（避免 CJK/emoji 多字节序列被截断）。
class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string &)> callback)
            : callback_(std::move(callback)) {}
    void processStream(const char *str, size_t len) {
        utf8Buffer_.append(str, len);
        size_t i = 0;
        std::string completeChars;
        while (i < utf8Buffer_.size()) {
            int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer_[i]));
            if (length == 0 || i + length > utf8Buffer_.size()) break;
            completeChars.append(utf8Buffer_, i, length);
            i += length;
        }
        utf8Buffer_ = utf8Buffer_.substr(i);
        if (!completeChars.empty()) callback_(completeChars);
    }
    static int utf8CharLength(unsigned char byte) {
        if ((byte & 0x80) == 0) return 1;
        if ((byte & 0xE0) == 0xC0) return 2;
        if ((byte & 0xF0) == 0xE0) return 3;
        if ((byte & 0xF8) == 0xF0) return 4;
        return 0;
    }
private:
    std::string utf8Buffer_;
    std::function<void(const std::string &)> callback_;
};

// ===== 流式批处理（Task 4 Step 2）：缓冲完整 UTF-8 字符，按字节/时间阈值批量回调 =====
// 首个完整可见字符立即 flush（首 delta 即时性），随后按 maxBytes/maxMs 聚合，EOS/收尾时 flush()
// 清空缓冲不丢字。上游 Utf8StreamProcessor 已保证传入的是完整字符，此处绝不切分 UTF-8 边界。
// 批处理策略是"仅本次生成"的：阈值经 nativeGenerateStream 参数传入（Balanced 16ms/256B；
// Maximum Speed 24–32ms/512–1024B），与 load 配置分离。
class StreamBatcher {
public:
    using Callback = std::function<void(const std::string &)>;
    StreamBatcher(Callback cb, int maxBytes, int maxMs)
            : cb_(std::move(cb)),
              maxBytes_(maxBytes > 0 ? maxBytes : 256),
              maxMs_(maxMs > 0 ? maxMs : 16) {}

    // 追加一个完整 UTF-8 字符；首个字符立即推，其余按阈值聚合，达标时内部 flush。
    void add(const std::string &utf8Char) {
        if (!firstFlushed_) {
            flushString(utf8Char);
            firstFlushed_ = true;
            return;
        }
        if (buffer_.empty()) firstCharTime_ = now();
        buffer_ += utf8Char;
        if ((int)buffer_.size() >= maxBytes_ || elapsedMsSince(firstCharTime_) >= maxMs_) {
            flush();
        }
    }

    // 强制 flush 剩余缓冲（EOS / abort / max tokens / 错误收尾时调用，保证不丢字）。
    void flush() {
        if (buffer_.empty()) return;
        flushString(buffer_);
        buffer_.clear();
    }

    int callbackCount() const { return callbackCount_; }
    size_t pushedBytes() const { return pushedBytes_; }

private:
    Callback cb_;
    int maxBytes_;
    int maxMs_;
    std::string buffer_;
    std::chrono::steady_clock::time_point firstCharTime_{};
    bool firstFlushed_ = false;
    int callbackCount_ = 0;
    size_t pushedBytes_ = 0;

    void flushString(const std::string &s) {
        if (s.empty() || !cb_) return;
        cb_(s);
        callbackCount_++;
        pushedBytes_ += s.size();
    }

    static std::chrono::steady_clock::time_point now() {
        return std::chrono::steady_clock::now();
    }

    static long long elapsedMsSince(const std::chrono::steady_clock::time_point &t) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t).count();
    }
};

// ===== 思考边界检测（Task 1 v2 契约）=====
// 在 UTF-8 输出路径旁路增量扫描 "</think>"：命中即记 hitUs（us，相对生成起点 t_start_us）。
// 只观察不吞改输出流，不影响 <eop>/EOS/UTF-8 行为。字节级比较安全：</think> 全 ASCII，而 UTF-8
// 续字节恒 ≥0x80，不可能与 ASCII 标记混淆，尾缓冲按字节截断不会产生误匹配。
class ThinkBoundaryScanner {
public:
    explicit ThinkBoundaryScanner(long long t_start_us) : t_start_us_(t_start_us) {}

    // 追加一个 chunk（单次回调，可能含多个完整 UTF-8 字符）；首次命中 </think> 记 hit_us_。
    // 命中判定在追加后的完整窗口上进行：Utf8StreamProcessor 会把单次 write 内全部完整字符合成
    // 一个 chunk（如 "</think>好"），若只取末 8 字节等值比较会因标签后紧跟正文而漏检；find 命中
    // 即记录并清空窗口，未命中再截断窗口（供跨 chunk 的标签头继续拼装）。
    void feed(const std::string &utf8Char) {
        if (hit_) return;
        tail_.append(utf8Char);
        const size_t kTagSize = 8;  // "</think>" 长度
        if (tail_.find("</think>") != std::string::npos) {
            hit_ = true;
            hit_us_ = nowUs() - t_start_us_;
            tail_.clear();  // 命中后窗口不再需要（hit_ 已短路后续 feed）
            return;
        }
        // 未命中：仅保留末 8 字节。标签全 ASCII、UTF-8 续字节恒 >=0x80，截断不会误匹配。
        if (tail_.size() > kTagSize) tail_ = tail_.substr(tail_.size() - kTagSize);
    }

    bool hit() const { return hit_; }
    long long hitUs() const { return hit_us_; }

private:
    static long long nowUs() {
        return std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    long long t_start_us_;
    bool hit_ = false;
    long long hit_us_ = -1;
    std::string tail_;
};

// ===== stepping 流状态（移植自 MnnLlmChat llm_session.cpp AndroidSteppingStreamState）=====
// processChunk 处理一个完整 UTF-8 字符：普通文本 -> full_text 累积 + StreamBatcher 批处理回调 + 每字符
// 轮询 abort；<eop> -> flush 缓冲后挂起。finalizePendingEop 收尾：置 generate_text_end（<eop> 由
// ArGeneration is_stop 分支写出，不推 UI、不入 full_text）。
struct AndroidSteppingStreamState {
    JNIEnv *env;
    StreamBatcher &batcher;
    bool &generate_text_end;
    bool &stop_requested;
    std::string &full_text;
    const char *result_log_tag;
    // v2（Task 1）：思考边界旁路扫描（可选，nullptr=不扫描）。不改变 <eop>/EOS/UTF-8 行为。
    ThinkBoundaryScanner *think_scanner = nullptr;
    bool pending_eop = false;

    void processChunk(const std::string &utf8Char) {
        const bool is_eop = utf8Char.find("<eop>") != std::string::npos;
        if (is_eop) {
            batcher.flush();  // EOS 收尾前把已缓冲内容推给 UI（不丢字）
            pending_eop = true;
            return;
        }
        full_text.append(utf8Char);
        // v2（Task 1）：思考边界旁路扫描（只观察，不吞改输出流；命中时刻由扫描器换算）。
        if (think_scanner) think_scanner->feed(utf8Char);
        batcher.add(utf8Char);
        // 每字符轮询 shouldAbort（不受批处理缓冲影响）：取消/策略停止在 1 token 内响应，
        // 无需等下一次 flush。
        if (env) stop_requested = stop_requested || should_abort(env);
    }

    void finalizePendingEop() {
        if (!pending_eop) return;
        MNN_LOGI("%s (gen=%zu bytes)", result_log_tag, full_text.size());
        generate_text_end = true;
        pending_eop = false;
    }
};

// EOS 检测（本 libMNN.so 行为，对齐 MNN 源码 ArGeneration::generate）：
//  - generate(1) 解码 1 个 token；命中 EOS token 时才把 end_with("<eop>") 写入流（is_stop 分支），
//    并置 status=MAX_TOKENS_FINISHED（每步都置，无论是否 EOS）。故 pending_eop（<eop> 入流）即真·EOS。
//  - CHECK_LLM_RUNNING 不拦 MAX_TOKENS_FINISHED，generate(int) 内 is_stop(current_token) 又防 EOS 后
//    再 decode，故无需手动复位 status；循环只需在 pending_eop 命中时收尾即可。
//  （MnnLlmChat 的 resolveAndroidSteppingEop/restoreAndroidSteppingStatusIfNeeded 是为「每步吐 <eop>」
//   的另一预编译运行时写的怪癖处理；本构建无该怪癖，套用反而会清掉真·EOS 导致跑到 max_tokens。）

// ===== 消息列表 JSON 解析 =====
// schema: [{"role":"user","content":"..."},...]（MnnBridge.toMessagesJson 产出）
// 仅处理本 schema：扫描 "role"/"content" 键取其字符串值，按数组顺序构造 ChatMessages。
static std::string parse_json_string_at(const std::string &s, size_t &i) {
    // 入口 s[i] == '"'
    if (i < s.size() && s[i] == '"') i++;
    std::string out;
    while (i < s.size() && s[i] != '"') {
        char c = s[i];
        if (c == '\\' && i + 1 < s.size()) {
            char e = s[i + 1];
            switch (e) {
                case '"':  out.push_back('"');  break;
                case '\\': out.push_back('\\'); break;
                case '/':  out.push_back('/');  break;
                case 'n':  out.push_back('\n'); break;
                case 'r':  out.push_back('\r'); break;
                case 't':  out.push_back('\t'); break;
                case 'b':  out.push_back('\b'); break;
                case 'f':  out.push_back('\f'); break;
                case 'u': {
                    if (i + 5 < s.size()) {
                        char hex[5] = {s[i+2], s[i+3], s[i+4], s[i+5], 0};
                        unsigned int cp = (unsigned int)strtoul(hex, nullptr, 16);
                        if (cp < 0x80) {
                            out.push_back((char)cp);
                        } else if (cp < 0x800) {
                            out.push_back((char)(0xC0 | (cp >> 6)));
                            out.push_back((char)(0x80 | (cp & 0x3F)));
                        } else {
                            out.push_back((char)(0xE0 | (cp >> 12)));
                            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
                            out.push_back((char)(0x80 | (cp & 0x3F)));
                        }
                        i += 4;
                    }
                    break;
                }
                default: out.push_back(e); break;
            }
            i += 2;
        } else {
            out.push_back(c);
            i++;
        }
    }
    if (i < s.size()) i++;  // 跳过闭合引号
    return out;
}

static void parse_messages(const std::string &s, ChatMessages &out) {
    size_t i = 0;
    while (i < s.size()) {
        size_t role_pos = s.find("\"role\"", i);
        if (role_pos == std::string::npos) break;
        size_t p = role_pos + 6;
        while (p < s.size() && (s[p] == ' ' || s[p] == ':' || s[p] == '\t' ||
                                s[p] == '\n' || s[p] == '\r')) p++;
        if (p >= s.size() || s[p] != '"') { i = role_pos + 6; continue; }
        std::string role = parse_json_string_at(s, p);

        size_t content_pos = s.find("\"content\"", p);
        if (content_pos == std::string::npos) break;
        p = content_pos + 9;
        while (p < s.size() && (s[p] == ' ' || s[p] == ':' || s[p] == '\t' ||
                                s[p] == '\n' || s[p] == '\r')) p++;
        if (p >= s.size() || s[p] != '"') { i = content_pos + 9; continue; }
        std::string content = parse_json_string_at(s, p);

        out.emplace_back(role, content);
        i = p;
    }
}

// ===== 诊断：读取模型目录下的配置文件前若干字节打日志 =====
// config_path 指向模型目录里的 config.json。读它（及同目录 llm_config.json）前 2KB 打日志，
// 便于在 logcat 核对模型自带配置是否含激进键（attention_mode/dynamic_option 等）--本工程
// set_config 已显式钉安全值覆盖，此日志仅用于核对模型原始配置，定位疑难时用。
static void log_model_config(const std::string &config_path) {
    auto dump = [](const std::string &path, const char *tag) {
        std::ifstream f(path, std::ios::binary);
        if (!f.is_open()) { MNN_LOGI("%s: (读不到 %s)", tag, path.c_str()); return; }
        std::string head(2048, '\0');
        f.read(&head[0], head.size());
        head.resize(f.gcount());
        MNN_LOGI("%s (%zu bytes head): %s", tag, head.size(), head.c_str());
    };
    dump(config_path, "model config.json");
    size_t slash = config_path.find_last_of('/');
    if (slash != std::string::npos) {
        dump(config_path.substr(0, slash) + "/llm_config.json", "model llm_config.json");
    }
}

// ===== GenerationSummary JSON 助手（Task 4 Step 3：nativeGenerateStream 返回紧凑摘要而非全量文本）=====

// JSON 字符串转义（异常消息/诊断文本可能含引号/换行，防止破坏摘要 JSON）。
static std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out.push_back(c); break;
        }
    }
    return out;
}

// 进入 generate 之前就失败（null handle / 消息解析失败）时的兜底摘要（v2 契约：BACKEND_FAILURE +
// errorCode；其余 v2 观测字段取默认/可空，thinkingConfigAccepted=null 表示「未尝试」）。
static std::string build_failure_summary(const char *stage, const char *code, const std::string &message) {
    std::ostringstream oss;
    oss << "{\"v\":2,\"completionReason\":\"BACKEND_FAILURE\",\"promptTokens\":0,"
        << "\"generatedTokens\":0,\"prefillUs\":0,\"decodeUs\":0,\"reuseKv\":-1,"
        << "\"callbackCount\":0,\"callbackBytes\":0,\"firstDeltaUs\":null,"
        << "\"errorStage\":\"" << stage << "\",\"errorMessage\":\""
        << json_escape(message) << "\""
        << ",\"decodeStepTokens\":1,\"thinkingConfigAccepted\":null"
        << ",\"reasoningEndUs\":null,\"firstBodyDeltaUs\":null"
        << ",\"errorCode\":\"" << code << "\"}";
    return oss.str();
}

// ===== v2 生成会话（Task 1：prefill/decode/finalize 三阶段状态机）=====
// 由 nativeGenerateStream 构造，按 prefill -> decode 循环 -> finalize 顺序推进；行为与 v1
// （generate(1) 逐 token stepping）完全一致：StreamBatcher / UTF-8 处理 / syncPromptCache /
// eraseHistory / <eop> 语义全部保留，仅把逻辑抽成清晰阶段函数并新增 v2 观测字段
// （decodeStepTokens / thinkingConfigAccepted / reasoningEndUs / firstBodyDeltaUs / errorCode）。
struct GenerationSession {
    JNIEnv *env = nullptr;
    Llm *llm = nullptr;
    int max_tokens = 0;     // 已解析（<=0 -> 2048）
    int decode_step = 1;    // clamp 后的实际步长（1..4）
    // ---- 流式/中断状态（与 v1 语义一致）----
    bool stop_requested = false;
    bool generate_text_end = false;
    std::string full_text;
    // ---- v2 观测（us，相对生成起点 t_start_us；-1=未观测到）----
    long long t_start_us = 0;             // 生成起点（prefill 前）
    long long first_delta_us = -1;        // 首回调时刻
    long long reasoning_end_us = -1;      // </think> 命中时刻
    long long first_body_delta_us = -1;   // </think> 之后首个回调时刻
    bool thinking_config_accepted = false;  // set_config(enable_thinking) 返回值
    // ---- 错误/指标状态 ----
    const char *error_stage = nullptr;    // nullptr=无错误；"PREFILL"/"DECODE"
    std::string error_message;
    std::string error_code;               // 错误码（与 error_stage 同生；空串=无错误）
    int gen_len_cb = 0;                   // 回调 gen_len 回退值（flush 首选 LlmContext::gen_seq_len）
    size_t kv_before_decode = 0;          // prefill 后、decode 前的 KV 长度（中断回滚基准）
    int current_size = 0;                 // 本轮 decode 步数（<= max_tokens）

    static long long nowUs() {
        return std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    long long elapsedUs() const { return nowUs() - t_start_us; }
};

// ===== 阶段一：prefill（采样/思考 set_config + response 前缀填充 + 中断即时检查 + KV 基准）=====
// 与 v1 行为一致：采样参数 best-effort 再 set_config 一次（load() 前已设，此处部分构建按 response
// 重建采样器则生效）；enable_thinking 经 jinja context set_config（返回值记 thinkingConfigAccepted
// 供 v2 遥测）；response(history, &os, "<eop>", 0) 仅 prefill，把 ostream 存入 LlmContext 供后续
// generate 继续写入；返回后立即轮询 abort（prefill 是 MNN 单次阻塞调用，期间无法安全跨线程
// 释放/中断，watchdog 只置 Kotlin abort）并取 KV 基准用于中断回滚。
static void session_prefill(GenerationSession &s, const ChatMessages &history, std::ostream &os,
                            float temperature, float top_p, float repeat_penalty,
                            bool enable_thinking) {
    // belt-and-suspenders：采样参数已在 nativeCreate 的 load() 前 set_config（保证生效，键名
    // topP/repetition_penalty 已修正）。此处按调用方参数再设一次 best-effort--若某些 MNN 构建
    // 按 response() 重建采样器则按此生效；若采样器确为 load() 一次性构建（本构建的行为），则此调用
    // 为 no-op，不影响正确性。键名同样用实测正确的 topP / repetition_penalty。
    char sample_conf[160];
    snprintf(sample_conf, sizeof(sample_conf),
             "{\"temperature\":%.4f,\"topP\":%.4f,\"repetition_penalty\":%.4f}",
             (double)temperature, (double)top_p, (double)repeat_penalty);
    s.llm->set_config(sample_conf);

    // 深度思考开关：经 jinja context 的 enable_thinking 控制（Qwen3 / DeepSeek-R1 等推理模型的 chat
    // 模板据此决定是否在 generation prompt 末尾插入 <think> 前缀）。set_config -> setChatTemplate ->
    // set_chat_template_context 立即更新 tokenizer 上下文，下一次 apply_chat_template（下方 response
    // 内）即生效，**无需重载模型**。enable_thinking=false 时模板注入空 <think>\n\n</think> 前缀，
    // 模型跳过推理直接作答；true 时插入 <think>\n，模型生成 reasoning 后 </think> 再作答。
    // 仅对含 enable_thinking 分支的 jinja 模板生效；Llama/Gemma 等无此分支的模板忽略该上下文，无害。
    // 出处：MNN dflash.cpp:84 set_config({"jinja":{"context":{"enable_thinking":false}}})；llm.cpp setChatTemplate。
    const char *think_conf = enable_thinking
        ? "{\"jinja\":{\"context\":{\"enable_thinking\":true}}}"
        : "{\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    s.thinking_config_accepted = s.llm->set_config(think_conf);
    if (!s.thinking_config_accepted) {
        MNN_LOGW("set_config enable_thinking 失败（继续用模型默认）: %s", think_conf);
    }

    MNN_LOGI("stepping prefill: msgs=%zu max_tokens=%d", history.size(), s.max_tokens);
    // prefill only（max_new_tokens=0），<eop> 作停止串。MNN 按模型 chat 模板格式化 history，
    // 并与上一轮 mCachedPromptText 做前缀匹配 -> reuse_kv 仅 prefill 新增 token。ostream 存入
    // LlmContext，供后续 generate(1) 继续写入。
    try {
        s.llm->response(history, &os, "<eop>", 0);
    } catch (const std::exception &e) {
        MNN_LOGE("prefill response 异常: %s", e.what());
        if (s.env->ExceptionCheck()) s.env->ExceptionClear();
        s.error_stage = "PREFILL";
        s.error_message = json_escape(e.what());
        s.error_code = "PREFILL_EXCEPTION";
    }

    // prefill 是 MNN 单次阻塞调用，期间无法安全跨线程释放/中断；watchdog 只置 Kotlin abort。
    // response 一返回立即检查，保证 timeout/cancel 不再额外 decode 1 token。
    s.stop_requested = s.stop_requested || should_abort(s.env);

    // prefill 后、decode 前的 KV 长度：中断时据此回滚生成的 token，使下一轮前缀仍命中缓存。
    try { s.kv_before_decode = s.llm->getCurrentHistory(); } catch (...) {}
}

// ===== 阶段二：decode 循环（stepping；步长 clamp 值；每步内逐 token 检查 EOS/maxTokens/abort）=====
// 与 v1 一致：每步 generate(1) 后若 <eop> 入流（pending_eop）即真·EOS -> 收尾退出；shouldAbort()
// 命中即停（1 token 内）。v2 多 token 步长（decode_step>1）下，内层循环逐 token 复核
// EOS/maxTokens/shouldAbort，保证取消粒度与 decodeStepTokens 一致——即使 step>1 也在 1 token 内响应。
static void session_decode(GenerationSession &s, AndroidSteppingStreamState &stream_state) {
    while (!s.stop_requested && !s.generate_text_end && s.current_size < s.max_tokens) {
        // 内层逐 token 复核 maxTokens：step>1 时一轮内也可能触顶，须在 generate(1) 前拦截
        // （与下方 EOS/abort 检查并列），保证 generatedTokens 恒 <= max_tokens。
        for (int i = 0; i < s.decode_step && s.current_size < s.max_tokens
                && !s.stop_requested && !s.generate_text_end && !s.error_stage; ++i) {
            try {
                s.llm->generate(1);
            } catch (const std::exception &e) {
                MNN_LOGE("generate(1) 异常: %s", e.what());
                if (s.env->ExceptionCheck()) s.env->ExceptionClear();
                s.error_stage = "DECODE";
                s.error_message = json_escape(e.what());
                s.error_code = "DECODE_EXCEPTION";
                break;
            }
            s.current_size++;
            s.gen_len_cb = s.current_size;  // 回调 gen_len 回退值（flush 首选 LlmContext::gen_seq_len）
            if (stream_state.pending_eop) {
                stream_state.finalizePendingEop();  // 真·EOS：<eop> 由 ArGeneration is_stop 分支写出
            }
            // v2：每步内逐 token 复核 abort（v1 靠外层 while 条件，等价；多 token 步长下保证
            // 取消粒度与 decodeStepTokens 一致，1 token 内停止）。
            if (!s.error_stage && should_abort(s.env)) s.stop_requested = true;
        }
        if (s.error_stage) break;  // 解码异常：与 v1 break 语义一致（仍走下方收尾）
    }
}

// ===== 阶段三：finalize（EOS 兜底收尾 + 缓冲 flush + KV 对齐 + 指标 + 完成原因 + v2 摘要 JSON）=====
// 与 v1 一致：pending EOS 兜底收尾、batcher.flush() 不丢字；正常结束 syncPromptCache、
// 中断 eraseHistory(kv_before_decode, 0) 回滚（避免半句污染前缀导致下一轮全量重 prefill）；
// 摘要按 v2 契约输出（新增 decodeStepTokens/thinkingConfigAccepted/reasoningEndUs/
// firstBodyDeltaUs/errorCode）。
static jstring session_finalize(GenerationSession &s, StreamBatcher &batcher, ChatMessages &history) {
    // KV 缓存对齐（多轮前缀复用关键）：
    //  - 正常结束：把 assistant 回复并入 history 后 syncPromptCache——MNN 据此对齐 mCachedPromptText
    //    （含本次回复），下一轮 response(full_history) 命中前缀 -> 仅 prefill 新 user。
    //  - 中断：eraseHistory(kv_before_decode, 0) 回滚到 prefill 后状态，避免被取消的半句污染前缀
    //    导致下一轮全量重 prefill。current_size==0（prefill 即被取消/未产 token）则无需回滚。
    //  - 策略截断（Kotlin onToken 返回 false -> abort）：走中断分支，显式失效 prompt cache，
    //    绝不 sync 被丢弃的生成后缀。
    if (!s.stop_requested) {
        if (!s.full_text.empty()) {
            history.emplace_back("assistant", s.full_text);
        }
        try {
            s.llm->syncPromptCache(history);
        } catch (const std::exception &e) {
            MNN_LOGW("syncPromptCache 异常（忽略）: %s", e.what());
        }
    } else if (s.current_size > 0) {
        try {
            s.llm->eraseHistory(s.kv_before_decode, 0);
        } catch (const std::exception &e) {
            MNN_LOGW("eraseHistory 异常（忽略）: %s", e.what());
        }
        MNN_LOGI("中断：已回滚 KV 至 prefill 后状态 kv_before=%zu generated=%d",
                 s.kv_before_decode, s.current_size);
    }

    // 指标 + 摘要：prompt_len（本轮 prefill 的 token 数，应远小于完整历史=前缀复用生效）/gen_len/
    // reuse_kv（本轮是否命中前缀缓存）。多轮中若 prompt_len 始终≈完整历史且 reuse_kv=0，说明前缀
    // 复用未生效，需排查 syncPromptCache / 历史一致性。
    int prompt_len = 0, gen_len = 0, reuse = -1;
    long long prefill_us = 0, decode_us = 0;
    {
        const LlmContext *ctx = s.llm->getContext();
        if (ctx) {
            prompt_len = ctx->prompt_len;
            gen_len = ctx->gen_seq_len;
            prefill_us = (long long)ctx->prefill_us;
            decode_us = (long long)ctx->decode_us;
            try { reuse = s.llm->reuse_kv() ? 1 : 0; } catch (...) { reuse = -1; }
            MNN_LOGI("response done: prompt_len=%d gen_len=%d all_seq_len=%d reuse_kv=%d "
                     "prefill_us=%lld decode_us=%lld stopped=%d cb=%d/%zu first_delta_us=%lld "
                     "reasoning_end_us=%lld first_body_delta_us=%lld step=%d",
                     prompt_len, gen_len, ctx->all_seq_len, reuse,
                     prefill_us, decode_us, (int)s.stop_requested,
                     batcher.callbackCount(), batcher.pushedBytes(), s.first_delta_us,
                     s.reasoning_end_us, s.first_body_delta_us, s.decode_step);
        }
    }

    // 完成原因（native best-effort；Kotlin 侧有更高优先级推导：策略截断/用户取消/后端失败）。
    const char *reason = "EOS";
    if (s.error_stage != nullptr) {
        reason = "BACKEND_FAILURE";
    } else if (s.stop_requested) {
        reason = "USER_CANCEL";  // Kotlin 侧区分 USER_CANCEL / POLICY_TRUNCATION（onToken 返回 false）
    } else if (!s.generate_text_end) {
        reason = "MAX_TOKENS";   // 循环因 current_size >= max_tokens 退出且未遇 EOS
    }

    // 紧凑版本化 GenerationSummary JSON（v2，Task 1；替代全量文本字节数组）。full_text 仅在
    // native 内部供 syncPromptCache 使用，不再整份拷贝回 Kotlin。
    std::ostringstream oss;
    oss << "{\"v\":2,\"completionReason\":\"" << reason << "\""
        << ",\"promptTokens\":" << prompt_len
        << ",\"generatedTokens\":" << gen_len
        << ",\"prefillUs\":" << prefill_us
        << ",\"decodeUs\":" << decode_us
        << ",\"reuseKv\":" << reuse
        << ",\"callbackCount\":" << batcher.callbackCount()
        << ",\"callbackBytes\":" << batcher.pushedBytes()
        << ",\"firstDeltaUs\":" << (s.first_delta_us >= 0 ? std::to_string(s.first_delta_us) : "null")
        << ",\"errorStage\":" << (s.error_stage ? ("\"" + std::string(s.error_stage) + "\"") : "null")
        << ",\"errorMessage\":" << (s.error_message.empty() ? "null" : ("\"" + s.error_message + "\""))
        // ---- v2 新增（Task 1）：decode 实际步长 / 思考配置接受 / 思考边界 / 首正文 / 错误码 ----
        << ",\"decodeStepTokens\":" << s.decode_step
        << ",\"thinkingConfigAccepted\":" << (s.thinking_config_accepted ? "true" : "false")
        << ",\"reasoningEndUs\":" << (s.reasoning_end_us >= 0 ? std::to_string(s.reasoning_end_us) : "null")
        << ",\"firstBodyDeltaUs\":" << (s.first_body_delta_us >= 0 ? std::to_string(s.first_body_delta_us) : "null")
        << ",\"errorCode\":" << (s.error_code.empty() ? "null" : ("\"" + s.error_code + "\""))
        << "}";
    MNN_LOGI("nativeGenerateStream summary: %s", oss.str().c_str());
    return s.env->NewStringUTF(oss.str().c_str());
}

// ===== JNI 导出 =====
// extern "C" 保证 JNI 名称查找不被 C++ name mangling 破坏（与 vulkan_jni.cpp 一致）。
extern "C" {

// ===== resolvedConfigJson 轻量校验辅助（Task 7）=====
// 提取顶层字符串键值：`"key":"value"`（value 不含转义引号）。仅用于日志/backend 判定，
// 不做完整 JSON 解析（完整解析由 MNN set_config 承担）。
static std::string extract_json_string(const std::string &json, const std::string &key) {
    std::string needle = "\"" + key + "\":\"";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return "";
    size_t val_start = pos + needle.size();
    size_t val_end = json.find('"', val_start);
    if (val_end == std::string::npos) return "";
    return json.substr(val_start, val_end - val_start);
}

static const size_t MAX_RESOLVED_CONFIG_BYTES = 8192;

JNIEXPORT jlong JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeCreate(
        JNIEnv *env, jobject thiz,
        jstring config_path, jstring resolved_config_json) {

    ensure_jni_cache(env);

    // 构建标记：logcat 见此串即确认新 libmnn_jni.so 已部署（不见 = APK 仍带旧 .so，需重装）。
    MNN_LOGI("mnn_jni build: batch-2026-08-09 (resolved plans: nativeCreate(configPath, resolvedConfigJson); schema 校验 + 原样 set_config; 移除 native 内隐式 CPU 安全重试)");

    const char *cfg = env->GetStringUTFChars(config_path, nullptr);
    if (!cfg) return 0;
    std::string config_str(cfg);
    env->ReleaseStringUTFChars(config_path, cfg);

    const char *rcj = env->GetStringUTFChars(resolved_config_json, nullptr);
    std::string resolved_json(rcj ? rcj : "");
    if (rcj) env->ReleaseStringUTFChars(resolved_config_json, rcj);

    // 防御性 schema/长度校验（完整 JSON 解析交给 MNN set_config；此处只挡明显畸形）。
    // Task 7：resolvedConfigJson 仅由 Kotlin InferenceProfileResolver 生成。
    if (resolved_json.empty()) {
        g_last_load_error = "resolvedConfigJson 为空";
        MNN_LOGE("%s", g_last_load_error.c_str());
        return 0;
    }
    if (resolved_json.size() > MAX_RESOLVED_CONFIG_BYTES) {
        g_last_load_error = "resolvedConfigJson 超长(" + std::to_string(resolved_json.size()) + "B)";
        MNN_LOGE("%s", g_last_load_error.c_str());
        return 0;
    }
    if (resolved_json.find("\"schemaVersion\":1") == std::string::npos) {
        g_last_load_error = "resolvedConfigJson schemaVersion 不匹配";
        MNN_LOGE("%s", g_last_load_error.c_str());
        return 0;
    }
    std::string backend_str = extract_json_string(resolved_json, "backend_type");
    if (backend_str.empty()) backend_str = "cpu";

    // 日志只打规范化配置的哈希摘要 + 安全摘要，不打完整 JSON（含 app 私有 cache_path 路径）。
    size_t resolved_id = std::hash<std::string>{}(resolved_json);
    MNN_LOGI("createLLM config=%s backend=%s resolved_id=%016zx len=%zu",
             config_str.c_str(), backend_str.c_str(), resolved_id, resolved_json.size());
    log_model_config(config_str);

    Llm *llm = Llm::createLLM(config_str);
    if (!llm) {
        g_last_load_error = "Llm::createLLM 返回 null（config.json 解析失败）";
        MNN_LOGE("%s", g_last_load_error.c_str());
        return 0;
    }

    // 原样透传 resolvedConfigJson（键已由 Kotlin 规范化排序，含安全通用键与 backend 专属键）。
    // schemaVersion 等未知键被 MNN config merge 忽略；已知键覆盖模型默认。采样器在 load() 前设好。
    MNN_LOGI("set_config(resolved): id=%016zx", resolved_id);
    if (!llm->set_config(resolved_json)) {
        MNN_LOGW("set_config 失败（继续用模型默认）");
    }

    bool ok = false;
    std::string load_err;
    try {
        ok = llm->load();
    } catch (const std::exception &e) {
        load_err = std::string("Llm::load 异常: ") + e.what();
        MNN_LOGE("%s", load_err.c_str());
        ok = false;
    }
    // Task 7：不再于 native 内做 CPU 安全配置隐式重试——CPU 优化/兼容两档由 Kotlin resolver
    // 生成两个 BackendAttempt，BackendManager 显式顺序尝试。
    if (!ok) {
        if (load_err.empty()) load_err = std::string("Llm::load() 失败 (backend=") + backend_str + ")";
        g_last_load_error = load_err;
        MNN_LOGE("%s", load_err.c_str());
        Llm::destroy(llm);
        return 0;
    }

    g_last_load_error.clear();
    MNN_LOGI("MNN 模型加载成功 backend=%s (resolved plan)", backend_str.c_str());
    return (jlong)llm;
}

JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGenerateStream(
        JNIEnv *env, jobject thiz,
        jlong handle, jstring messages_json,
        jint max_tokens, jfloat temperature,
        jfloat top_p, jfloat repeat_penalty,
        jboolean enable_thinking,
        jint batch_bytes, jint batch_ms,
        jint decode_step_tokens) {   // v2（Task 1）：末尾追加；clamp 到 [1,4]，缺省/非法值 -> 1

    Llm *llm = (Llm *)handle;
    if (!llm) return env->NewStringUTF(
        build_failure_summary("LOAD", "LOAD_NULL_HANDLE", "null handle").c_str());

    const char *jstr = env->GetStringUTFChars(messages_json, nullptr);
    if (!jstr) return env->NewStringUTF(
        build_failure_summary("LOAD", "LOAD_MESSAGES_JSON", "messages_json 不可读").c_str());
    std::string json(jstr);
    env->ReleaseStringUTFChars(messages_json, jstr);

    ChatMessages history;
    parse_messages(json, history);
    if (history.empty()) {
        MNN_LOGE("消息列表解析为空，放弃推理");
        return env->NewStringUTF(
            build_failure_summary("LOAD", "LOAD_PARSE_EMPTY", "消息列表解析为空").c_str());
    }

    // v2：decode 步长 clamp 到 [1,4]（非法值/缺省 -> 1，等价 v1 逐 token 行为）。
    int decode_step = decode_step_tokens < 1 ? 1 : (decode_step_tokens > 4 ? 4 : decode_step_tokens);

    // ===== v2 生成会话：prefill -> decode 循环 -> finalize 三阶段（Task 1）=====
    GenerationSession session;
    session.env = env;
    session.llm = llm;
    session.max_tokens = max_tokens > 0 ? (int)max_tokens : 2048;
    session.decode_step = decode_step;
    session.t_start_us = GenerationSession::nowUs();  // 生成起点：所有 v2 时延观测的相对基准

    // 思考边界旁路扫描（v2）：</think> 命中时刻 -> reasoning_end_us（us，相对生成起点）。
    ThinkBoundaryScanner think_scanner(session.t_start_us);

    // 流式批处理（Task 4 Step 2）：首个完整可见字符立即 flush（首 delta 即时性），其余按
    // batch_bytes/batch_ms 聚合后再回调。策略是"仅本次生成"的，经参数由 Kotlin 传入
    // （Task 6 性能模式接入前先用 Balanced 默认 256B/16ms），与 load 配置分离。
    StreamBatcher batcher(
        [&session, &think_scanner](const std::string &batch) {
            // 首回调记 first_delta_us（首 delta 即时性）；</think> 之后的首个回调记
            // first_body_delta_us（v2：首个非思考正文 delta；无思考段则恒不置位）。
            if (think_scanner.hit() && session.reasoning_end_us < 0) {
                session.reasoning_end_us = think_scanner.hitUs();
            }
            if (session.first_delta_us < 0) {
                session.first_delta_us = session.elapsedUs();
            }
            if (session.reasoning_end_us >= 0 && session.first_body_delta_us < 0) {
                session.first_body_delta_us = session.elapsedUs();
            }
            // 回调用真实已生成 token 数（供浮窗实时 tps）。批处理后回调次数≠token 数，不能再用
            // 回调计数。LlmContext::gen_seq_len 在解码线程同步读（无并发），flush 时已含正在生成的
            // token，最准确；读失败回退 stepping 步数计数（gen_len_cb，每步 generate(1) 后更新）。
            int gen_len_now = session.gen_len_cb;
            try {
                const LlmContext *c = session.llm->getContext();
                if (c) gen_len_now = c->gen_seq_len;
            } catch (...) {}
            push_bytes(session.env, batch.data(), (int)batch.size(), gen_len_now);
        },
        (int)batch_bytes, (int)batch_ms);

    AndroidSteppingStreamState stream_state{
            env, batcher, session.generate_text_end, session.stop_requested, session.full_text,
            "nativeGenerateStream Result", &think_scanner
    };
    Utf8StreamProcessor processor([&stream_state](const std::string &ch) {
        stream_state.processChunk(ch);
    });
    LlmStreamBuffer stream_buffer([&processor](const char *s, size_t len) {
        processor.processStream(s, len);
    });
    std::ostream os(&stream_buffer);

    // 清掉上一轮可能残留的终态，确保 prefill 顺利进入（response()->generate_init 会再置 RUNNING，
    // 此处仅双保险）。本构建无「每步吐 <eop>」怪癖，不用 MnnLlmChat 的 resolveAndroidSteppingEop。
    {
        const LlmContext *ctx = llm->getContext();
        if (ctx && (ctx->status == LlmStatus::MAX_TOKENS_FINISHED ||
                    ctx->status == LlmStatus::NORMAL_FINISHED)) {
            const_cast<LlmContext *>(ctx)->status = LlmStatus::RUNNING;
        }
    }

    // 阶段一：prefill（采样/思考 set_config + response 前缀填充 + abort 即时检查 + KV 基准）。
    session_prefill(session, history, os, temperature, top_p, repeat_penalty,
                    enable_thinking == JNI_TRUE);

    // 阶段二：decode 循环（stepping；步长 clamp 值；每步内逐 token 检查 EOS/maxTokens/abort）。
    session_decode(session, stream_state);
    stream_state.finalizePendingEop();  // 兜底（到 max_tokens 未遇 EOS，或循环被 break）
    batcher.flush();                    // 收尾 flush：abort / max_tokens / 错误路径均不丢已缓冲内容

    // 阶段三：finalize（KV 对齐 + 指标 + 完成原因 + v2 摘要 JSON）。
    return session_finalize(session, batcher, history);
}

JNIEXPORT void JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    // 真·中断标志在 Kotlin 侧 MnnBridge.abort（stepping 循环每步经 shouldAbort 轮询，1 token 内停）。
    // 此处仅日志，保留接口供未来扩展。
    (void)env; (void)thiz; (void)handle;
    MNN_LOGI("nativeStop（stepping：shouldAbort 已置位，循环将在 1 token 内退出）");
}

JNIEXPORT void JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeRelease(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    Llm *llm = (Llm *)handle;
    if (!llm) return;
    try {
        Llm::destroy(llm);
    } catch (const std::exception &e) {
        MNN_LOGE("Llm::destroy 异常（忽略）: %s", e.what());
    }
    MNN_LOGI("MNN 模型已释放");
}

JNIEXPORT jfloatArray JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetMetrics(
        JNIEnv *env, jobject thiz, jlong handle) {
    // 返回 [tokensPerSecond, prefillUs, decodeUs, promptLen, genLen, reuseKv]
    // reuseKv: 最近一次 response 是否复用了 KV 前缀缓存（1=是/0=否/-1=取不到），供验证多轮前缀复用。
    float tps = 0.f, prefill_us = 0.f, decode_us = 0.f, prompt_len = 0.f, gen_len = 0.f, reuse_kv = 0.f;
    Llm *llm = (Llm *)handle;
    if (llm) {
        const LlmContext *ctx = llm->getContext();
        if (ctx) {
            prefill_us = (float)ctx->prefill_us;
            decode_us  = (float)ctx->decode_us;
            prompt_len = (float)ctx->prompt_len;
            gen_len    = (float)ctx->gen_seq_len;
            if (decode_us > 0.f && gen_len > 0.f) {
                tps = gen_len / (decode_us / 1e6f);
            }
        }
        try { reuse_kv = llm->reuse_kv() ? 1.f : 0.f; } catch (...) { reuse_kv = -1.f; }
    }
    float metrics[6] = {tps, prefill_us, decode_us, prompt_len, gen_len, reuse_kv};
    jfloatArray result = env->NewFloatArray(6);
    if (result) env->SetFloatArrayRegion(result, 0, 6, metrics);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetLastError(
        JNIEnv *env, jobject thiz) {
    // 返回最近一次 nativeCreate 的加载失败原因（g_last_load_error）。空串=无错误/上次成功。
    // 供 MnnBackend.initialize 在 nativeCreate 返回 0 时取真实原因填 lastErrorMessage，再由
    // BackendManager 汇总上报，定位「所有后端均加载失败」的芯片相关根因。
    (void)thiz;
    return env->NewStringUTF(g_last_load_error.c_str());
}

// 运行时信息握手：回传 JNI ABI 版本、钉定 MNN commit、native build ID 与能力集。
// Kotlin 侧 MnnBridge 加载库后解析一次（nativeGetRuntimeInfo），与期望 ABI/commit 不符时
// 置 nativeAvailable=false 并暴露诊断（runtimeDiagnostic），在模型加载前拦截不兼容的 native 栈。
// 能力集反映本 libMNN.so 钉定构建的编译期特性（LLM/低内存/OpenCL/ARM82 开启，QNN 关闭）；
// summary_v2 在 v2 生成契约（Task 1）落地后追加（Kotlin MnnBridge.hasSummaryV2Capability 查询，
// Task 8 发布门禁据此拒绝旧 native；v1 兼容路径继续可用）。
// sampler_hot_update：Wave 2 引擎补丁——set_config 携带采样标量时热重建采样管线
// （Kotlin resolver 据此在 load 配置里省略温度等标量，调参不再触发整模重载；
// 旧 .so 无此能力时 Kotlin 保持 legacy 行为逐位不变）。
// JSON 形如 {"abiVersion":1,"mnnCommit":"af0142...","nativeBuildId":"...","capabilities":["mmap",...]}
JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetRuntimeInfo(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
    static const char *kFmt =
        "{\"abiVersion\":%d,"
        "\"mnnCommit\":\"%s\","
        "\"nativeBuildId\":\"%s\","
        "\"capabilities\":[\"mmap\",\"cached_mmap\",\"reuse_kv\",\"opencl\",\"arm82\",\"summary_v2\",\"sampler_hot_update\"]}";
    char buf[512];
    snprintf(buf, sizeof(buf), kFmt,
             (int)CHAT_MNN_JNI_ABI, CHAT_MNN_COMMIT, CHAT_MNN_BUILD_ID);
    return env->NewStringUTF(buf);
}

}  // extern "C"
