/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.v2;

import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.*;
import org.apache.hadoop.util.*;
import org.gridgain.grid.*;
import org.gridgain.grid.hadoop.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

/**
 * Hadoop API v2 splitter.
 */
public class GridHadoopV2Splitter {
    /** */
    private static final String[] EMPTY_HOSTS = {};

    /**
     * @param ctx Job context.
     * @return Collection of mapped splits.
     * @throws GridException If mapping failed.
     */
    public static Collection<GridHadoopInputSplit> splitJob(JobContext ctx) throws GridException {
        try {
            InputFormat<?, ?> format = ReflectionUtils.newInstance(ctx.getInputFormatClass(), ctx.getConfiguration());

            assert format != null;

            List<InputSplit> splits = format.getSplits(ctx);

            Collection<GridHadoopInputSplit> res = new ArrayList<>(splits.size());

            int id = 0;

            for (InputSplit nativeSplit : splits) {
                if (nativeSplit instanceof FileSplit) {
                    FileSplit s = (FileSplit)nativeSplit;

                    res.add(new GridHadoopFileBlock(s.getLocations(), s.getPath().toUri(), s.getStart(), s.getLength()));
                }
                else
                    res.add(new GridHadoopSplitWrapper(id, nativeSplit, nativeSplit.getLocations()));

                id++;
            }

            return res;
        }
        catch (IOException | ClassNotFoundException e) {
            throw new GridException(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new GridInterruptedException(e);
        }
    }

    /**
     * @param clsName Input split class name.
     * @param in Input stream.
     * @param hosts Optional hosts.
     * @return File block or {@code null} if it is not a {@link FileSplit} instance.
     * @throws GridException If failed.
     */
    public static GridHadoopFileBlock readFileBlock(String clsName, DataInput in, @Nullable String[] hosts)
        throws GridException {
        if (!FileSplit.class.getName().equals(clsName))
            return null;

        FileSplit split = new FileSplit();

        try {
            split.readFields(in);
        }
        catch (IOException e) {
            throw new GridException(e);
        }

        if (hosts == null)
            hosts = EMPTY_HOSTS;

        return new GridHadoopFileBlock(hosts, split.getPath().toUri(), split.getStart(), split.getLength());
    }
}
