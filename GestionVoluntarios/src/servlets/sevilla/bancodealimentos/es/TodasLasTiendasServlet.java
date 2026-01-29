package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

@WebServlet("/todas-las-tiendas")
public class TodasLasTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(TodasLasTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class TiendaDTO {
        public int id;
        public String nombre;
        public String direccion;
        public BigDecimal lat;
        public BigDecimal lon;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<TiendaDTO> tiendas = new ArrayList<>();
        String sql = "SELECT codigo AS id, denominacion AS nombre, Direccion AS direccion, Lat AS lat, Lon AS lon FROM tiendas WHERE disponible = 'S'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                TiendaDTO t = new TiendaDTO();
                t.id = rs.getInt("id");
                t.nombre = rs.getString("nombre");
                t.direccion = rs.getString("direccion");
                t.lat = rs.getBigDecimal("lat");
                t.lon = rs.getBigDecimal("lon");
                tiendas.add(t);
            }
            
            mapper.writeValue(response.getWriter(), tiendas);

        } catch (SQLException e) {
            logger.error("Error SQL al obtener todas las tiendas.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
    }
}
