#include "llama.h"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {
constexpr char kBase64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64_encode(const uint8_t * bytes, size_t size) {
    std::string out;
    out.reserve(((size + 2) / 3) * 4);
    for (size_t i = 0; i < size; i += 3) {
        const uint32_t group = (uint32_t(bytes[i]) << 16) |
            (i + 1 < size ? uint32_t(bytes[i + 1]) << 8 : 0) |
            (i + 2 < size ? uint32_t(bytes[i + 2]) : 0);
        out.push_back(kBase64[(group >> 18) & 63]);
        out.push_back(kBase64[(group >> 12) & 63]);
        out.push_back(i + 1 < size ? kBase64[(group >> 6) & 63] : '=');
        out.push_back(i + 2 < size ? kBase64[group & 63] : '=');
    }
    return out;
}

int base64_value(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

std::string base64_decode(const std::string & input) {
    std::string out;
    int value = 0;
    int bits = -8;
    for (char c : input) {
        if (c == '=') break;
        const int next = base64_value(c);
        if (next < 0) return {};
        value = (value << 6) + next;
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<char>((value >> bits) & 0xff));
            bits -= 8;
        }
    }
    return out;
}

std::vector<llama_token> tokenize(llama_model * model, const std::string & text) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(4096);
    int count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), tokens.size(), true, false);
    if (count < 0) {
        tokens.resize(-count);
        count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), tokens.size(), true, false);
    }
    if (count <= 0 || count > 2048) return {};
    tokens.resize(count);
    return tokens;
}

std::vector<std::vector<float>> embed_batch(
        llama_model * model,
        llama_context * context,
        const std::vector<std::vector<llama_token>> & token_groups) {
    int total_tokens = 0;
    for (const auto & tokens : token_groups) total_tokens += static_cast<int>(tokens.size());
    llama_batch batch = llama_batch_init(total_tokens, 0, static_cast<int32_t>(token_groups.size()));
    int at = 0;
    for (size_t sequence = 0; sequence < token_groups.size(); ++sequence) {
        for (size_t position = 0; position < token_groups[sequence].size(); ++position, ++at) {
            batch.token[at] = token_groups[sequence][position];
            batch.pos[at] = static_cast<llama_pos>(position);
            batch.n_seq_id[at] = 1;
            batch.seq_id[at][0] = static_cast<llama_seq_id>(sequence);
            batch.logits[at] = position + 1 == token_groups[sequence].size();
        }
    }
    batch.n_tokens = at;
    llama_memory_clear(llama_get_memory(context), false);
    if (llama_encode(context, batch) != 0) {
        llama_batch_free(batch);
        return {};
    }
    const int dimension = llama_model_n_embd(model);
    std::vector<std::vector<float>> output;
    for (size_t sequence = 0; sequence < token_groups.size(); ++sequence) {
        float * values = llama_get_embeddings_seq(context, static_cast<llama_seq_id>(sequence));
        if (!values || dimension <= 0) {
            llama_batch_free(batch);
            return {};
        }
        output.emplace_back(values, values + dimension);
        double length = 0.0;
        for (float value : output.back()) length += value * value;
        length = std::sqrt(length);
        if (length <= 0.0) {
            llama_batch_free(batch);
            return {};
        }
        for (float & value : output.back()) value = static_cast<float>(value / length);
    }
    llama_batch_free(batch);
    return output;
}
}

int main(int argc, char ** argv) {
    if (argc != 2 && argc != 4) {
        std::cerr << "usage: grace_offline_embedding /path/to/embeddinggemma.gguf [input.tsv output.tsv]\n";
        return 2;
    }
    std::ifstream input_file;
    std::ofstream output_file;
    std::istream * input = &std::cin;
    std::ostream * output = &std::cout;
    if (argc == 4) {
        input_file.open(argv[2]);
        output_file.open(argv[3], std::ios::out | std::ios::app);
        if (!input_file || !output_file) return 2;
        input = &input_file;
        output = &output_file;
    }
    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(argv[1], model_params);
    if (!model) return 1;
    llama_context_params context_params = llama_context_default_params();
    // n_ctx is shared by the four batching sequences, so reserve four full
    // 2,048-token contexts instead of silently reducing a source passage to
    // 512 tokens.
    context_params.n_ctx = 8192;
    context_params.n_batch = 8192;
    context_params.n_ubatch = 8192;
    context_params.n_seq_max = 4;
    context_params.n_threads = 8;
    context_params.n_threads_batch = 8;
    context_params.embeddings = true;
    context_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    context_params.offload_kqv = false;
    llama_context * context = llama_init_from_model(model, context_params);
    if (!context) {
        llama_model_free(model);
        return 1;
    }
    std::string line;
    int completed = 0;
    while (true) {
        std::vector<std::string> ids;
        std::vector<std::vector<llama_token>> token_groups;
        for (int group = 0; group < 4 && std::getline(*input, line); ++group) {
            const size_t tab = line.find('\t');
            if (tab == std::string::npos) return 2;
            ids.push_back(line.substr(0, tab));
            token_groups.push_back(tokenize(model, base64_decode(line.substr(tab + 1))));
            if (token_groups.back().empty()) {
                std::cerr << "Could not embed row " << ids.back() << " (it may exceed 2048 tokens)\n";
                return 1;
            }
        }
        if (ids.empty()) break;
        const auto vectors = embed_batch(model, context, token_groups);
        if (vectors.size() != ids.size()) return 1;
        for (size_t group = 0; group < ids.size(); ++group) {
            *output << ids[group] << '\t' << base64_encode(reinterpret_cast<const uint8_t *>(vectors[group].data()), vectors[group].size() * sizeof(float)) << '\n';
        }
        output->flush();
        completed += static_cast<int>(ids.size());
        if (completed % 32 == 0) std::cerr << "Embedded " << completed << " passages\n";
    }
    llama_free(context);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
