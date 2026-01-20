// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet que devuelve las listas de supervisores y zonas para los filtros
 * de la pantalla de gestiÃ³n de tiendas.
 */
@WebServlet("/admin-filtros-tiendas")
public class AdminFiltrosTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute("isAdmin") != null && (boolean) session.getAttribute("isAdmin");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        StringBuilder jsonBuilder = new StringBuilder("{");

        try (Connection conn = DatabaseUtil.getConnection()) {
            // Obtener lista de supervisores distintos
            jsonBuilder.append("\"supervisores\":[");
            String sqlSupervisores = "SELECT DISTINCT Supervisor FROM tiendas WHERE Supervisor IS NOT NULL AND Supervisor != '' ORDER BY Supervisor";
            try (PreparedStatement stmt = conn.prepareStatement(sqlSupervisores);
                 ResultSet rs = stmt.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) jsonBuilder.append(",");
                    jsonBuilder.append("\"").append(escapeJson(rs.getString("Supervisor"))).append("\"");
                    first = false;
                }
            }
            jsonBuilder.append("],");

            // Obtener lista de zonas distintas
            jsonBuilder.append("\"zonas\":[");
            String sqlZonas = "SELECT DISTINCT Zona FROM tiendas WHERE Zona IS NOT NULL AND Zona != '' ORDER BY Zona";
            try (PreparedStatement stmt = conn.prepareStatement(sqlZonas);
                 ResultSet rs = stmt.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) jsonBuilder.append(",");
                    jsonBuilder.append("\"").append(escapeJson(rs.getString("Zona"))).append("\"");
                    first = false;
                }
            }
            jsonBuilder.append("]");

        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los filtros.");
            return;
        }

        jsonBuilder.append("}");
        out.print(jsonBuilder.toString());
        out.flush();
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}


