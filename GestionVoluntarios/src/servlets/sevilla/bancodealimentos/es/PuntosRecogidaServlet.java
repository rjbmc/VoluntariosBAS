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

/**
 * Servlet que devuelve los puntos de recogida (tiendas, almacenes)
 * en formato JSON.
 * NOTA: Este servlet puede ser obsoleto o redundante con TodasLasTiendasServlet.
 */
@WebServlet("/puntos-recogida")
public class PuntosRecogidaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(PuntosRecogidaServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO simple para la serialización JSON
    public static class PuntoRecogidaDTO {
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

        List<PuntoRecogidaDTO> puntos = new ArrayList<>();
        String sql = "SELECT codigo AS id, denominacion AS nombre, Direccion AS direccion, Lat AS lat, Lon AS lon FROM tiendas WHERE disponible = 'S'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                PuntoRecogidaDTO punto = new PuntoRecogidaDTO();
                punto.id = rs.getInt("id");
                punto.nombre = rs.getString("nombre");
                punto.direccion = rs.getString("direccion");
                punto.lat = rs.getBigDecimal("lat");
                punto.lon = rs.getBigDecimal("lon");
                
                puntos.add(punto);
            }
            
            // 3. Serialización automática con Jackson
            mapper.writeValue(response.getWriter(), puntos);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar los puntos de recogida.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los puntos de recogida.");
        }
    }
}