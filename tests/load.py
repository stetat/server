import asyncio

opened = 0      # connections successfully established
done = 0        # connections that got a response back
failed = 0      # connections that errored (refused, too many files, etc.)
repeating = 0
prev_stats = ()
async def hit(i):
    global opened, done, failed
    try:
        r, w = await asyncio.open_connection("localhost", 8080)
        opened += 1
        w.write(b"GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n")
        await w.drain()
        await r.read(100)
        w.close()
        done += 1
    except Exception as e:
        failed += 1
        if failed <= 5:                       # don't spam: only show the first few
            print("client fail:", i, e)





async def reporter():
    global repeating, prev_stats
    """Print a live tally every second so the run isn't a silent black box."""
    while True:
        await asyncio.sleep(1)
        print(f"opened={opened}  done={done}  failed={failed}")

        if (opened, done, failed) == prev_stats:
            repeating += 1
        else:
            repeating = 0

        if repeating >= 3:
            print("plateaue detected - cancelling outstanding connections")
            work.cancel()
            break

        prev_stats = (opened, done, failed)

async def main(n):
    rep = asyncio.create_task(reporter())     # background progress printer
    work = asyncio.gather(*(hit(i) for i in range(n)))

    try:
        await work
    except asyncio.CancelledError:
        print("aborted: server plateaued")
    finally:
        rep.cancel()
                               # stop the reporter once all connections finish
    print(f"FINAL: opened={opened}  done={done}  failed={failed}")

asyncio.run(main(100000))
