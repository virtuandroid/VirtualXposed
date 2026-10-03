#include "helpers.h"
#include <jni.h>
#include <lsplant.hpp>
#include <android/log.h>
#include <dobby.h>
#include <dlfcn.h>
#include "../common/logger.h"
#include <cstdio>
#include <sys/mman.h>
#include <android/log.h>
#include <iomanip>
#include <sstream>
#include <sys/mman.h>
#include <unistd.h>
#include <cstring>
#include <cstdint>
#include <android/log.h>

static uint32_t GetInstructionLength(const uint8_t *code) {
    uint32_t len = 0;
    while (code[len] == 0x66 || code[len] == 0x67 ||
           (code[len] >= 0x40 && code[len] <= 0x4F) ||
           code[len] == 0xF0 || code[len] == 0xF2 || code[len] == 0xF3) {
        len++;
    }

    uint8_t opcode = code[len++];

    if (opcode == 0x0F) {
        uint8_t opcode2 = code[len++];
        if (opcode2 >= 0x80 && opcode2 <= 0x8F) return len + 4;
        uint8_t modrm = code[len++];
        uint8_t mod = (modrm >> 6) & 3;
        uint8_t rm = modrm & 7;
        if (mod == 0 && rm == 5) len += 4;
        else if (mod == 1) len += 1;
        else if (mod == 2) len += 4;
        return len;
    }

    if (opcode >= 0x50 && opcode <= 0x57) return len;
    if (opcode == 0x55) return len;
    if (opcode == 0x89 || opcode == 0x8B) {
        uint8_t modrm = code[len++];
        uint8_t mod = (modrm >> 6) & 3;
        uint8_t rm = modrm & 7;
        if (mod == 0 && rm == 5) len += 4;
        else if (mod == 1) len += 1;
        else if (mod == 2) len += 4;
        return len;
    }
    if (opcode == 0x83) return len + 2;
    if (opcode == 0x81) return len + 5;
    if (opcode == 0xE9) return len + 4;
    if (opcode == 0xEB) return len + 1;

    return len + 2;
}

// TODO replace this function or dobby as a whole
/**
 * Terrible replacement for Dobby on x86_64.
 * Dobby (probably) overwrites instructions due to variable
 * length instruction sizes on x86_64. This leads to crashes when hooking some
 * specific functions (for example: art::mirror::Class::SetStatus) and running WebView.
 *
 * This function is therefore LLM-generated, for a lack of better options,
 * but it seems to work well.
 */
int helpers::InstallHook(void *target, void *hooker, void **original) {
    if (!target || !hooker) return RT_FAILED;

    // Calculate bytes to copy (>= 14 bytes)
    uint32_t copied_bytes = 0;
    const auto *code = reinterpret_cast<const uint8_t *>(target);
    while (copied_bytes < 14) {
        uint32_t insn_len = GetInstructionLength(code + copied_bytes);
        if (insn_len == 0) insn_len = 1;
        copied_bytes += insn_len;
    }

    // Allocate an independent RXW page dynamically for THIS hook's trampoline
    long page_size = sysconf(_SC_PAGESIZE);
    size_t tramp_size = copied_bytes + 14; // prologue bytes + 14-byte absolute jump

    auto *tramp_buffer = static_cast<uint8_t *>(mmap(
            nullptr,
            page_size,
            PROT_READ | PROT_WRITE | PROT_EXEC,
            MAP_ANONYMOUS | MAP_PRIVATE,
            -1,
            0
    ));

    if (tramp_buffer == MAP_FAILED) {
        LOG_ERROR("mmap failed to allocate trampoline memory");
        return RT_FAILED;
    }

    // Copy original instructions to the dedicated trampoline buffer
    std::memcpy(tramp_buffer, target, copied_bytes);

    // Fixup RIP-relative displacements in copied instructions
    for (uint32_t offset = 0; offset < copied_bytes;) {
        uint32_t insn_len = GetInstructionLength(tramp_buffer + offset);
        uint8_t *insn = tramp_buffer + offset;

        for (uint32_t i = 0; i < insn_len; ++i) {
            if ((insn[i] & 0xC7) == 0x05 && (i + 4 < insn_len)) {
                int32_t orig_disp;
                std::memcpy(&orig_disp, &insn[i + 1], sizeof(int32_t));

                uintptr_t orig_target =
                        reinterpret_cast<uintptr_t>(target) + offset + insn_len + orig_disp;
                uintptr_t new_src = reinterpret_cast<uintptr_t>(tramp_buffer) + offset + insn_len;
                int64_t new_disp =
                        static_cast<int64_t>(orig_target) - static_cast<int64_t>(new_src);

                auto new_disp32 = static_cast<int32_t>(new_disp);
                std::memcpy(&insn[i + 1], &new_disp32, sizeof(int32_t));
                break;
            }
        }
        offset += insn_len;
    }

    // Append return jump (target + copied_bytes) at the end of the trampoline
    uint8_t *tramp_jmp = tramp_buffer + copied_bytes;
    tramp_jmp[0] = 0xFF;
    tramp_jmp[1] = 0x25;
    tramp_jmp[2] = 0x00;
    tramp_jmp[3] = 0x00;
    tramp_jmp[4] = 0x00;
    tramp_jmp[5] = 0x00;

    uintptr_t return_addr = reinterpret_cast<uintptr_t>(target) + copied_bytes;
    std::memcpy(tramp_jmp + 6, &return_addr, sizeof(return_addr));

    if (original) {
        *original = reinterpret_cast<void *>(tramp_buffer);
    }

    // Make target memory page writable
    uintptr_t page_start = reinterpret_cast<uintptr_t>(target) & ~(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page_start), page_size * 2,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOG_ERROR("Failed to set RWX on target page");
        munmap(tramp_buffer, page_size);
        return RT_FAILED;
    }

    // Write 14-byte absolute indirect jump patch: JMP [RIP+0] -> [Hooker Address]
    uint8_t patch[14];
    patch[0] = 0xFF;
    patch[1] = 0x25;
    patch[2] = 0x00;
    patch[3] = 0x00;
    patch[4] = 0x00;
    patch[5] = 0x00;

    auto hook_addr = reinterpret_cast<uintptr_t>(hooker);
    std::memcpy(patch + 6, &hook_addr, sizeof(hook_addr));

    std::memset(target, 0x90, copied_bytes); // Pad remaining preamble bytes with NOPs
    std::memcpy(target, patch, 14);

    // Restore RX protection on target page and flush cache
    mprotect(reinterpret_cast<void *>(page_start), page_size * 2, PROT_READ | PROT_EXEC);
    __builtin___clear_cache(reinterpret_cast<char *>(target),
                            reinterpret_cast<char *>(target) + copied_bytes);

    return RT_SUCCESS;
}
