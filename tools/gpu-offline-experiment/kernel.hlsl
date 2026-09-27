// Original bounded integer mixing workload. Not Minecraft terrain or lighting.
StructuredBuffer<uint> Input : register(t0);
RWStructuredBuffer<uint> Output : register(u0);
cbuffer Parameters : register(b0) { uint Count; uint Rounds; uint Seed; uint Padding; }

[numthreads(256, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= Count) return;
    uint value = Input[id.x];
    for (uint round = 0; round < Rounds; ++round) {
        value ^= value >> 16;
        value *= 0x7feb352d;
        value ^= value >> 15;
        value *= 0x846ca68b;
        value ^= value >> 16;
        value += Seed + round;
    }
    Output[id.x] = value;
}
