package gov.cms.ab2d.coverage.query;

import gov.cms.ab2d.coverage.model.CoverageMembership;
import gov.cms.ab2d.coverage.model.Identifiers;
import lombok.val;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class GetCoverageMembership extends CoverageV3BaseQuery {

    private final CoverageMembershipRowMapper mapper;
    public GetCoverageMembership(DataSource dataSource) {
        super(dataSource);
        this.mapper = new CoverageMembershipRowMapper();
    }

    // Each union arm is ordered and limited on its own so Postgres can read it from the
    // (patient_id, contract, year, month, current_mbi) unique index and stop after :limit rows,
    // instead of de-duplicating every row of the contract on each page. The first :limit rows
    // of the union are always among the first :limit rows of each arm. The opt-out join and
    // year filter only look at a row's own columns, so they are applied inside each arm.
    private static final String SELECT_COVERAGE_WITHOUT_OPTOUT_WITHOUT_CURSOR =
    """
    select patient_id, current_mbi, year, month from (
        (select * from v3.coverage_v3
           where contract = :contract
           and current_mbi is not null
           order by patient_id, year, month, current_mbi
           limit :limit)
        union
        (select * from v3.coverage_v3_historical
           where contract = :contract
           and current_mbi is not null
           order by patient_id, year, month, current_mbi
           limit :limit)
        order by patient_id, year asc, month asc
        limit :limit
    )
    where year in (:years)
    order by patient_id asc, year asc, month asc
    """;

    private static final String SELECT_COVERAGE_WITHOUT_OPTOUT_WITH_CURSOR =
    """
    select patient_id, current_mbi, year, month from (
        (select * from v3.coverage_v3
           where contract = :contract
           and current_mbi is not null
           and patient_id >= :patient_id
           order by patient_id, year, month, current_mbi
           limit :limit)
        union
        (select * from v3.coverage_v3_historical
           where contract = :contract
           and current_mbi is not null
           and patient_id >= :patient_id
           order by patient_id, year, month, current_mbi
           limit :limit)
        order by patient_id, year asc, month asc
        limit :limit
    )
    where year in (:years)
    order by patient_id asc, year asc, month asc
    """;

    private static final String SELECT_COVERAGE_WITH_OPTOUT_WITHOUT_CURSOR =
    """
    select patient_id, current_mbi, year, month from
    (
       (select c.* from v3.coverage_v3 c
           left join current_mbi m on c.current_mbi = m.mbi
           where c.contract = :contract
           and c.current_mbi is not null
           and c.year in (:years)
           and m.share_data is not false
           order by c.patient_id, c.year, c.month, c.current_mbi
           limit :limit)
       union
       (select c.* from v3.coverage_v3_historical c
           left join current_mbi m on c.current_mbi = m.mbi
           where c.contract = :contract
           and c.current_mbi is not null
           and c.year in (:years)
           and m.share_data is not false
           order by c.patient_id, c.year, c.month, c.current_mbi
           limit :limit)
    ) as union_results
    order by patient_id asc, year asc, month asc
    limit :limit
    """;

    private static final String SELECT_COVERAGE_WITH_OPTOUT_WITH_CURSOR =
    """
    select patient_id, current_mbi, year, month from
    (
       (select c.* from v3.coverage_v3 c
           left join current_mbi m on c.current_mbi = m.mbi
           where c.contract = :contract
           and c.current_mbi is not null
           and c.patient_id >= :patient_id
           and c.year in (:years)
           and m.share_data is not false
           order by c.patient_id, c.year, c.month, c.current_mbi
           limit :limit)
       union
       (select c.* from v3.coverage_v3_historical c
           left join current_mbi m on c.current_mbi = m.mbi
           where c.contract = :contract
           and c.current_mbi is not null
           and c.patient_id >= :patient_id
           and c.year in (:years)
           and m.share_data is not false
           order by c.patient_id, c.year, c.month, c.current_mbi
           limit :limit)
    ) as union_results
    order by patient_id asc, year asc, month asc
    limit :limit
    """;

    public List<CoverageMembership> getCoverageMembership(
            final String contract,
            final List<Integer> years,
            final boolean optOutOn,
            final long limit
    ) {
        return getCoverageMembership(contract, years, optOutOn, limit, null);
    }

    public List<CoverageMembership> getCoverageMembership(
            final String contract,
            final List<Integer> years,
            final boolean optOutOn,
            final long limit,
            final Long cursorPatientId
    ) {

        val hasCursor = cursorPatientId != null;
        final String query;
        if (optOutOn) {
            if (hasCursor) {
                query = SELECT_COVERAGE_WITH_OPTOUT_WITH_CURSOR;
            } else {
                query = SELECT_COVERAGE_WITH_OPTOUT_WITHOUT_CURSOR;
            }
        } else {
            if (hasCursor) {
                query = SELECT_COVERAGE_WITHOUT_OPTOUT_WITH_CURSOR;
            } else {
                query = SELECT_COVERAGE_WITHOUT_OPTOUT_WITHOUT_CURSOR;
            }
        }

        val parameters = new MapSqlParameterSource()
                .addValue("contract", contract)
                .addValue("years", years)
                .addValue("limit", limit);

        if (hasCursor) {
            parameters.addValue("patient_id", cursorPatientId);
        }

        val template = new NamedParameterJdbcTemplate(this.dataSource);
        return template.query(query, parameters, this.mapper);
    }

    private static class CoverageMembershipRowMapper implements RowMapper<CoverageMembership> {
        @Override
        public CoverageMembership mapRow(ResultSet rs, int rowNum) throws SQLException {
            val patientId = rs.getLong(1);
            val currentMbi = rs.getString(2);
            val year = rs.getInt(3);
            val month = rs.getInt(4);
            val identifiers = Identifiers.ofV3(patientId, currentMbi);
            return new CoverageMembership(identifiers, year, month);
        }
    }
}
