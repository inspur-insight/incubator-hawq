package org.apache.hawq.pxf.plugins.jdbc;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.hawq.pxf.api.Fragmenter;
import org.apache.hawq.pxf.api.FragmentsStats;
import org.apache.hawq.pxf.api.UserDataException;
import org.apache.hawq.pxf.plugins.jdbc.utils.DbProduct;
import org.apache.hawq.pxf.plugins.jdbc.utils.ByteUtil;
import org.apache.hawq.pxf.api.Fragment;
import org.apache.hawq.pxf.api.utilities.InputData;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Fragmenter class for JDBC data resources.
 *
 * Extends the {@link Fragmenter} abstract class, with the purpose of transforming
 * an input data path  (an JDBC Database table name  and user request parameters)  into a list of regions
 * that belong to this table.
 * <p>
 * <h4>The parameter Patterns </h4>
 * There are three  parameters,  the format is as follows:<p>
 * <pre>
 * <code>PARTITION_BY=column_name:column_type&RANGE=start_value[:end_value]&INTERVAL=interval_num[:interval_unit]</code>
 * </pre>
 * The <code>PARTITION_BY</code> parameter can be split by colon(':'),the <code>column_type</code> current supported : <code>date,int,enum</code> .
 * The Date format is 'yyyy-MM-dd'. <p>
 * The <code>RANGE</code> parameter can be split by colon(':') ,used to identify the starting range of each fragment.
 * The range is left-closed, ie:<code> '>= start_value AND < end_value' </code>.If the <code>column_type</code> is <code>int</code>,
 * the <code>end_value</code> can be empty. If the <code>column_type</code>is <code>enum</code>,the parameter <code>RANGE</code> can be empty. <p>
 * The <code>INTERVAL</code> parameter can be split by colon(':'), indicate the interval value of one fragment.
 * When <code>column_type</code> is <code>date</code>,this parameter must be split by colon, and <code>interval_unit</code> can be <code>year,month,day</code>.
 * When <code>column_type</code> is <code>int</code>, the <code>interval_unit</code> can be empty.
 * When <code>column_type</code> is <code>enum</code>,the <code>INTERVAL</code> parameter can be empty.
 * </p>
 * <p>
 * The syntax examples is :<p>
 * <code>PARTITION_BY=createdate:date&RANGE=2008-01-01:2010-01-01&INTERVAL=1:month'</code> <p>
 * <code>PARTITION_BY=year:int&RANGE=2008:2010&INTERVAL=1</code> <p>
 * <code>PARTITION_BY=grade:enum&RANGE=excellent:good:general:bad</code>
 * </p>
 *
 */
public class JdbcPartitionFragmenter extends Fragmenter {
    String[] partitionBy = null;
    String[] range = null;
    String[] interval = null;
    PartitionType partitionType = null;
    String partitionColumn = null;
    IntervalType intervalType = null;
    int intervalNum = 1;

    enum PartitionType {
        DATE,
        INT,
        ENUM;

        public static PartitionType getType(String str) {
            return valueOf(str.toUpperCase());
        }
    }

    enum IntervalType {
        DAY,
        MONTH,
        YEAR;

        public static IntervalType type(String str) {
            return valueOf(str.toUpperCase());
        }
    }

    //The unit interval, in milliseconds, that is used to estimate the number of slices for the date partition type
    static Map<IntervalType, Long> intervals = new HashMap<IntervalType, Long>();

    static {
        intervals.put(IntervalType.DAY, (long) 24 * 60 * 60 * 1000);
        //30 days
        intervals.put(IntervalType.MONTH, (long) 30 * 24 * 60 * 60 * 1000);
        //365 days
        intervals.put(IntervalType.YEAR, (long) 365 * 30 * 24 * 60 * 60 * 1000);
    }

    /**
     * Constructor for JdbcPartitionFragmenter.
     *
     * @param inConf input data such as which Jdbc table to scan
     * @throws UserDataException
     */
    public JdbcPartitionFragmenter(InputData inConf) throws UserDataException {
        super(inConf);
        if (inConf.getUserProperty("PARTITION_BY") == null)
            return;
        try {
            partitionBy = inConf.getUserProperty("PARTITION_BY").split(":");
            partitionColumn = partitionBy[0];
            partitionType = PartitionType.getType(partitionBy[1]);

            range = inConf.getUserProperty("RANGE").split(":");

            //parse and validate parameter-INTERVAL
            if (inConf.getUserProperty("INTERVAL") != null) {
                interval = inConf.getUserProperty("INTERVAL").split(":");
                intervalNum = Integer.parseInt(interval[0]);
                if (interval.length > 1)
                    intervalType = IntervalType.type(interval[1]);
            }
            if (intervalNum < 1)
                throw new UserDataException("The parameter{INTERVAL} must > 1, but actual is '" + intervalNum + "'");
        } catch (IllegalArgumentException e1) {
            throw new UserDataException(e1);
        } catch (UserDataException e2) {
            throw e2;
        }
    }

    /**
     * Returns statistics for Jdbc table. Currently it's not implemented.
     */
    @Override
    public FragmentsStats getFragmentsStats() throws Exception {
        throw new UnsupportedOperationException("ANALYZE for Jdbc plugin is not supported");
    }

    /**
     * Returns list of fragments containing all of the
     * Jdbc table data.
     *
     * @return a list of fragments
     */
    @Override
    public List<Fragment> getFragments() throws Exception {
        if (partitionType == null) {
            byte[] fragmentMetadata = null;
            byte[] userData = null;
            Fragment fragment = new Fragment(inputData.getDataSource(), null, fragmentMetadata, userData);
            fragments.add(fragment);
            return prepareHosts(fragments);
        }
        switch (partitionType) {
            case DATE: {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                int currInterval = intervalNum;

                //start of range = start of first fragment
                Calendar fragStart = Calendar.getInstance();
                fragStart.setTime(df.parse(range[0]));
                Calendar rangeEnd = Calendar.getInstance();
                rangeEnd.setTime(df.parse(range[1]));
                while (fragStart.before(rangeEnd)) {
                    Calendar fragEnd = (Calendar) fragStart.clone();
                    switch (intervalType) {
                        case DAY:
                            fragEnd.add(Calendar.DAY_OF_MONTH, currInterval);
                            break;
                        case MONTH:
                            fragEnd.add(Calendar.MONTH, currInterval);
                            break;
                        case YEAR:
                            fragEnd.add(Calendar.YEAR, currInterval);
                            break;
                    }
                    if (fragEnd.after(rangeEnd))
                        fragEnd = (Calendar) rangeEnd.clone();

                    //make metadata of this fragment , converts the date to a millisecond,then get bytes.
                    byte[] msStart = ByteUtil.getBytes(fragStart.getTimeInMillis());
                    byte[] msEnd = ByteUtil.getBytes(fragEnd.getTimeInMillis());
                    byte[] fragmentMetadata = ByteUtil.mergeBytes(msStart, msEnd);

                    byte[] userData = new byte[0];
                    Fragment fragment = new Fragment(inputData.getDataSource(), null, fragmentMetadata, userData);
                    fragments.add(fragment);

                    //continue next fragment.
                    fragStart = fragEnd;
                }
                break;
            }
            case INT: {
                int rangeStart = Integer.parseInt(range[0]);
                int rangeEnd = Integer.parseInt(range[1]);
                int currInterval = intervalNum;

                //validate : curr_interval > 0
                int fragStart = rangeStart;
                while (fragStart < rangeEnd) {
                    int fragEnd = fragStart + currInterval;
                    if (fragEnd > rangeEnd) fragEnd = rangeEnd;

                    byte[] bStart = ByteUtil.getBytes(fragStart);
                    byte[] bEnd = ByteUtil.getBytes(fragEnd);
                    byte[] fragmentMetadata = ByteUtil.mergeBytes(bStart, bEnd);

                    byte[] userData = new byte[0];
                    Fragment fragment = new Fragment(inputData.getDataSource(), null, fragmentMetadata, userData);
                    fragments.add(fragment);

                    //continue next fragment.
                    fragStart = fragEnd;// + 1;
                }
                break;
            }
            case ENUM:
                for (String frag : range) {
                    byte[] fragmentMetadata = frag.getBytes();
                    Fragment fragment = new Fragment(inputData.getDataSource(), null, fragmentMetadata, new byte[0]);
                    fragments.add(fragment);
                }
                break;
        }

        return prepareHosts(fragments);
    }

    /**
     * For each fragment , assigned a host address.
     * In Jdbc Plugin, 'replicas' is the host address of the PXF engine that is running, not the database engine.
     * Since the other PXF host addresses can not be probed, only the host name of the current PXF engine is returned.
     * @param fragments a list of fragments
     * @return a list of fragments that assigned hosts.
     * @throws Exception
     */
    public static List<Fragment> prepareHosts(List<Fragment> fragments) throws Exception {
        for (Fragment fragment : fragments) {
            String pxfHost = InetAddress.getLocalHost().getHostAddress();
            String[] hosts = new String[]{pxfHost};
            fragment.setReplicas(hosts);
        }

        return fragments;
    }

    public String buildFragmenterSql(String dbName, String originSql) {
        byte[] meta = inputData.getFragmentMetadata();
        if (meta == null)
            return originSql;

        DbProduct dbProduct = DbProduct.getDbProduct(dbName);

        StringBuilder sb = new StringBuilder(originSql);
        if (!originSql.contains("WHERE"))
            sb.append(" WHERE 1=1 ");

        sb.append(" AND ");
        switch (partitionType) {
            case DATE: {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                //parse metadata of this fragment
                //validate：the length of metadata == 16 (long)
                byte[][] newb = ByteUtil.splitBytes(meta, 8);
                Date fragStart = new Date(ByteUtil.toLong(newb[0]));
                Date fragEnd = new Date(ByteUtil.toLong(newb[1]));

                sb.append(partitionColumn).append(" >= ").append(dbProduct.wrapDate(df.format(fragStart)));
                sb.append(" AND ");
                sb.append(partitionColumn).append(" < ").append(dbProduct.wrapDate(df.format(fragEnd)));

                break;
            }
            case INT: {
                //validate：the length of metadata ==8 （int)
                byte[][] newb = ByteUtil.splitBytes(meta, 4);
                int fragStart = ByteUtil.toInt(newb[0]);
                int fragEnd = ByteUtil.toInt(newb[1]);
                sb.append(partitionColumn).append(" >= ").append(fragStart);
                sb.append(" AND ");
                sb.append(partitionColumn).append(" < ").append(fragEnd);
                break;
            }
            case ENUM:
                sb.append(partitionColumn).append("='").append(new String(meta)).append("'");
                break;
        }
        return sb.toString();
    }
}
