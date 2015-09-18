package org.revenj;

import org.revenj.patterns.*;
import org.revenj.security.PermissionManager;
import org.revenj.security.GlobalPermission;
import org.revenj.security.RolePermission;
import rx.Observable;
import rx.Subscription;

import java.io.Closeable;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class RevenjPermissionManager implements PermissionManager, Closeable {

	private final Optional<SearchableRepository<GlobalPermission>> globalRepository;
	private final Optional<SearchableRepository<RolePermission>> rolesRepository;
	private final boolean defaultPermissions;

	private final Subscription globalSubscription;
	private final Subscription roleSubscription;

	private Map<String, Boolean> globalPermissions;
	private Map<String, List<Pair>> rolePermissions;

	private Map<String, Boolean> cache = new HashMap<>();
	private final Map<Class<?>, List<Filter>> registeredFilters = new HashMap<>();

	private final class Pair {
		public final String name;
		public final boolean isAllowed;

		public Pair(String name, boolean isAllowed) {
			this.name = name;
			this.isAllowed = isAllowed;
		}
	}

	private final class Filter<T> {
		public final Specification<T> specification;
		public final String role;
		public final boolean inverse;

		public Filter(Specification<T> specification, String role, boolean inverse) {
			this.specification = specification;
			this.role = role;
			this.inverse = inverse;
		}
	}

	private boolean permissionsChanged = true;

	public RevenjPermissionManager(ServiceLocator locator) {
		this(locator,
				locator.resolve(Properties.class),
				new Generic<Observable<Callable<GlobalPermission>>>() {
				}.resolve(locator),
				new Generic<Observable<Callable<RolePermission>>>() {
				}.resolve(locator),
				new Generic<Optional<SearchableRepository<GlobalPermission>>>() {
				}.resolve(locator),
				new Generic<Optional<SearchableRepository<RolePermission>>>() {
				}.resolve(locator));
	}

	public RevenjPermissionManager(
			ServiceLocator locator,
			Properties properties,
			Observable<Callable<GlobalPermission>> globalChanges,
			Observable<Callable<RolePermission>> roleChanges,
			Optional<SearchableRepository<GlobalPermission>> globalRepository,
			Optional<SearchableRepository<RolePermission>> rolesRepository) {
		String openByDefault = properties.getProperty("Permissions.OpenByDefault");
		defaultPermissions = openByDefault == null || "true".equals(openByDefault);
		globalSubscription = globalChanges.subscribe(c -> permissionsChanged = true);
		roleSubscription = roleChanges.subscribe(c -> permissionsChanged = true);
		this.globalRepository = globalRepository;
		this.rolesRepository = rolesRepository;
	}

	private void checkPermissions() {
		if (!permissionsChanged)
			return;
		if (globalRepository.isPresent()) {
			globalPermissions =
					globalRepository.get().search().stream().collect(
							Collectors.toMap(GlobalPermission::getName, GlobalPermission::getIsAllowed));
		}
		if (rolesRepository.isPresent()) {
			rolePermissions =
					rolesRepository.get().search().stream().collect(
							Collectors.groupingBy(
									RolePermission::getName,
									Collectors.mapping(it -> new Pair(it.getRoleID(), it.getIsAllowed()), Collectors.toList())));
		}
		cache = new HashMap<>();
		permissionsChanged = false;
	}

	private boolean checkOpen(String[] parts, int len) {
		if (len < 1) {
			return defaultPermissions;
		}
		boolean isOpen;
		String name = String.join(".", Arrays.copyOf(parts, len));
		Boolean found = globalPermissions.get(name);
		return found != null ? found : checkOpen(parts, len - 1);
	}

	@Override
	public boolean canAccess(String identifier, Principal user) {
		checkPermissions();
		String target = identifier != null ? identifier : "";
		String id = user.getName() + ":" + target;
		Boolean exists = cache.get(id);
		if (exists != null) {
			return exists;
		}
		String[] parts = target.split(".");
		boolean isAllowed = checkOpen(parts, parts.length);
		List<Pair> permissions;
		for (int i = parts.length; i > 0; i--) {
			String subName = String.join(".", Arrays.copyOf(parts, i));
			permissions = rolePermissions.get(subName);
			if (permissions != null) {
				Optional<Pair> found =
						permissions.stream().filter(it -> user.getName().equals(it.name)).findFirst();
				//TODO: missing subject logic
				//.flatMap(permissions.stream().filter(it -> user.implies(Subject..getSubject(it.Name))));
				if (found.isPresent()) {
					isAllowed = found.get().isAllowed;
					break;
				}
			}
		}
		Map<String, Boolean> newCache = new HashMap<>(cache);
		newCache.put(id, isAllowed);
		cache = newCache;
		return isAllowed;
	}

	@Override
	public <T extends DataSource> Query<T> applyFilters(Class<T> manifest, Principal user, Query<T> data) {
		List<Filter> registered = registeredFilters.get(manifest);
		if (registered != null) {
			Query<T> result = data;
			for (Filter r : registered) {
				//TODO: use roles or subjects instead
				if (user.getName().equals(r.role) != r.inverse)
					result = result.filter(r.specification);
			}
			return result;
		}
		return data;
	}

	@Override
	public <T extends DataSource> Collection<T> applyFilters(Class<T> manifest, Principal user, Collection<T> data) {
		List<Filter> registered = registeredFilters.get(manifest);
		if (registered != null) {
			Stream<T> result = data.stream();
			for (Filter r : registered) {
				//TODO: use roles or subjects instead
				if (user.getName().equals(r.role) != r.inverse)
					result = result.filter(r.specification);
			}
			return result.collect(Collectors.toList());
		}
		return data;
	}

	@Override
	public <T extends DataSource> Closeable registerFilter(Class<T> manifest, Specification<T> filter, String role, boolean inverse) {
		List<Filter> registered = registeredFilters.get(manifest);
		if (registered == null) {
			registered = new ArrayList<>();
			registeredFilters.put(manifest, registered);
		}
		Filter item = new Filter(filter, role, inverse);
		List<Filter> reg = registered;
		reg.add(item);
		return () -> reg.remove(item);
	}

	public void close() {
		globalSubscription.unsubscribe();
		roleSubscription.unsubscribe();
	}
}