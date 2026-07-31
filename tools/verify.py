"""Verify a reported hit against a real Minecraft 1.16.1 server.

The searcher is a reimplementation; this is the only thing that settles whether a
column really exists. It builds a throwaway world on the seed, makes the server
generate the target chunk and enough neighbours for it to be decorated, then reads
the saved region file back.

    python tools/verify.py <server.jar> <seed> <x> <y> <z> [--blocks]

    --blocks   dump the blocks around the position instead of listing cane,
               which is what to look at when a hit fails to reproduce

You supply the server jar; it is not redistributable. Any vanilla 1.16.1
minecraft_server jar works. Java must be on PATH.

Note the cane survives the post-generation flood — FlowingFluid.canHoldFluid
refuses to spread into sugar cane — so a column placed at generation time is still
there even though the air pocket around it has become water. That is why the
verified find at seed 1500050556 sits underwater and is still visible.
"""
import os
import shutil
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcnbt import read_chunk, block_grid  # noqa: E402

GLYPH = {'air': '.', 'cave_air': ':', 'water': '~', 'stone': '#', 'gravel': 'v',
         'dirt': 'd', 'grass_block': 'g', 'sand': 's', 'clay': 'l',
         'sugar_cane': 'C', 'lava': 'L', 'granite': '#', 'diorite': '#',
         'andesite': '#', 'obsidian': 'O', 'magma_block': 'M', 'seagrass': ',',
         'tall_seagrass': ',', 'kelp_plant': 'k', 'kelp': 'k', 'bedrock': 'B',
         'sandstone': '#', 'coal_ore': 'o', 'iron_ore': 'o', 'gold_ore': 'o',
         'redstone_ore': 'o', 'lapis_ore': 'o', 'diamond_ore': 'o'}


def generate(server_jar, seed, cx, cz, radius=2):
    """Runs a server just long enough to generate the area, returns the world path."""
    here = os.path.dirname(os.path.abspath(__file__))
    run = os.path.join(here, 'run_%d_%d_%d' % (seed, cx, cz))
    if os.path.exists(run):
        shutil.rmtree(run)
    os.makedirs(run)
    shutil.copy(server_jar, os.path.join(run, 'server.jar'))
    with open(os.path.join(run, 'eula.txt'), 'w') as f:
        f.write('eula=true\n')
    with open(os.path.join(run, 'server.properties'), 'w') as f:
        f.write('level-seed=%d\nlevel-name=world\nonline-mode=false\n'
                'view-distance=3\nmax-tick-time=-1\nspawn-protection=0\n'
                'sync-chunk-writes=true\n' % seed)

    print('starting server in %s (seed %d)' % (run, seed), flush=True)
    proc = subprocess.Popen(['java', '-Xmx4G', '-jar', 'server.jar', 'nogui'],
                            cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)
    ready = threading.Event()
    saved = threading.Event()

    def pump():
        for line in proc.stdout:
            if 'Done (' in line:
                ready.set()
            if 'Saved the game' in line or 'Saved the world' in line:
                saved.set()

    threading.Thread(target=pump, daemon=True).start()
    if not ready.wait(300):
        proc.kill()
        sys.exit('server did not start - is this a vanilla 1.16.1 server jar?')

    def send(command):
        proc.stdin.write(command + '\n')
        proc.stdin.flush()

    # A chunk only runs its features once its neighbours are carved, so load a
    # block around the target rather than the single chunk.
    send('forceload add %d %d %d %d' % ((cx - radius) * 16, (cz - radius) * 16,
                                        (cx + radius) * 16 + 15, (cz + radius) * 16 + 15))
    time.sleep(20)
    send('save-all flush')
    saved.wait(60)
    time.sleep(5)
    send('stop')
    proc.wait(120)
    print('server stopped', flush=True)
    return os.path.join(run, 'world')


def main():
    if len(sys.argv) < 6:
        print(__doc__)
        sys.exit(2)
    server_jar = sys.argv[1]
    seed = int(sys.argv[2])
    tx, ty, tz = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
    blocks = '--blocks' in sys.argv
    cx, cz = tx >> 4, tz >> 4

    world = generate(server_jar, seed, cx, cz)

    def region(ccx, ccz):
        return os.path.join(world, 'region', 'r.%d.%d.mca' % (ccx >> 5, ccz >> 5))

    grids = {}
    for ccx in range(cx - 1, cx + 2):
        for ccz in range(cz - 1, cz + 2):
            level = read_chunk(region(ccx, ccz), ccx, ccz)
            grids[(ccx, ccz)] = (block_grid(level) if level else None,
                                 level.get('Status') if level else None)

    def block(x, y, z):
        grid = grids.get((x >> 4, z >> 4), (None, None))[0]
        return 'unloaded' if grid is None else grid.get((x & 15, y, z & 15), 'air')

    print()
    print('target chunk status: %s' % (grids[(cx, cz)][1],))
    if blocks:
        print('slice at z=%d, x %d..%d' % (tz, tx - 8, tx + 8))
        print('      ' + ''.join(str(abs(x) % 10) for x in range(tx - 8, tx + 9)))
        for y in range(min(ty + 10, 255), max(ty - 8, 0) - 1, -1):
            row = ''.join(GLYPH.get(block(x, y, tz), '?') for x in range(tx - 8, tx + 9))
            print('y=%3d %s%s' % (y, row, '  <== reported y' if y == ty else ''))
        print('      . air  : cave_air  ~ water  # stone  v gravel  d dirt  s sand  C cane')
        return

    found = []
    for (ccx, ccz), (grid, _) in grids.items():
        if grid is None:
            continue
        cane = {k for k, v in grid.items() if v == 'sugar_cane'}
        for (lx, y, lz) in sorted(cane):
            if (lx, y - 1, lz) in cane:
                continue                      # not the bottom of a stack
            height = 0
            while (lx, y + height, lz) in cane:
                height += 1
            found.append((height, ccx * 16 + lx, y, ccz * 16 + lz,
                          grid.get((lx, y - 1, lz))))

    print('reported: x=%d y=%d z=%d' % (tx, ty, tz))
    if not found:
        print('NO sugar cane in the 3x3 chunks around the target - the hit is false')
    for height, x, y, z, below in sorted(found, reverse=True):
        marker = '   <== the reported column' if (x, y, z) == (tx, ty, tz) else ''
        print('  height %d at %d,%d,%d on %s%s' % (height, x, y, z, below, marker))
    print()
    print('tallest column found: %d' % (max([f[0] for f in found]) if found else 0))


if __name__ == '__main__':
    main()
