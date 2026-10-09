use bevy_ecs::prelude::{Component, QueryState, World as BevyWorld};
use shipyard::{Component as ShipComponent, IntoIter, View, ViewMut};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Component, ShipComponent)]
pub struct Key {
    pub world: u64,
    pub slot: usize,
    pub generation: u64,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Position(pub [i64; 3]);
#[derive(Clone, Copy, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Velocity(pub [i64; 3]);
#[derive(Clone, Copy, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Bounds(pub [i64; 3]);
#[derive(Clone, Copy, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Lifecycle(pub u32);
#[derive(Clone, Copy, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Behavior(pub u16);
#[derive(Clone, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Cold {
    pub capabilities: Vec<u64>,
    pub nbt: Vec<u8>,
    pub java_token: u64,
    pub mods: Vec<(u32, Vec<u8>)>,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Component, ShipComponent)]
pub struct Extension(pub u64);
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Hot {
    pub position: Position,
    pub velocity: Velocity,
    pub bounds: Bounds,
    pub lifecycle: Lifecycle,
    pub behavior: Behavior,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Row {
    pub key: Key,
    pub hot: Hot,
    pub cold: Cold,
    pub extension: Option<Extension>,
}

pub fn fixture(key: Key, seed: u64) -> Row {
    Row {
        key,
        hot: Hot {
            position: Position([
                (seed * 17 % 8192) as i64,
                (seed * 43 % 8192) as i64,
                (seed * 101 % 8192) as i64,
            ]),
            velocity: Velocity([
                (seed % 3) as i64 - 1,
                ((seed / 3) % 3) as i64 - 1,
                ((seed / 9) % 3) as i64 - 1,
            ]),
            bounds: Bounds([1 + (seed % 4) as i64; 3]),
            lifecycle: Lifecycle(1),
            behavior: Behavior((seed % 7) as u16),
        },
        cold: Cold {
            capabilities: vec![seed, seed + 1],
            nbt: (0..32).map(|i| (seed + i) as u8).collect(),
            java_token: seed + 100_000,
            mods: if seed.is_multiple_of(4) {
                vec![(7, vec![seed as u8; 8])]
            } else {
                vec![]
            },
        },
        extension: None,
    }
}

pub trait Store: Default {
    type Handle: Copy + Eq;
    fn spawn(&mut self, row: Row) -> Self::Handle;
    fn despawn(&mut self, handle: Self::Handle) -> bool;
    fn hot(&self, handle: Self::Handle) -> Option<Hot>;
    fn read(&self, handle: Self::Handle) -> Option<Row>;
    fn velocity(&mut self, handle: Self::Handle, value: [i64; 3]);
    fn lifecycle(&mut self, handle: Self::Handle, value: u32);
    fn extension(&mut self, handle: Self::Handle, value: Option<Extension>);
    fn tick(&mut self);
    fn scan(&mut self) -> Vec<(Key, Hot)>;
}

#[derive(Default)]
pub struct Soa {
    keys: Vec<Key>,
    positions: Vec<Position>,
    velocities: Vec<Velocity>,
    bounds: Vec<Bounds>,
    lifecycle: Vec<Lifecycle>,
    behavior: Vec<Behavior>,
    cold: Vec<Cold>,
    extension: Vec<Option<Extension>>,
    indices: Vec<Option<usize>>,
}
impl Soa {
    fn index(&self, key: Key) -> Option<usize> {
        let i = (*self.indices.get(key.slot)?)?;
        (self.keys[i] == key).then_some(i)
    }
    fn at(&self, i: usize) -> Hot {
        Hot {
            position: self.positions[i],
            velocity: self.velocities[i],
            bounds: self.bounds[i],
            lifecycle: self.lifecycle[i],
            behavior: self.behavior[i],
        }
    }
}
impl Store for Soa {
    type Handle = Key;
    fn spawn(&mut self, row: Row) -> Key {
        let k = row.key;
        self.indices
            .resize(self.indices.len().max(k.slot + 1), None);
        assert!(self.indices[k.slot].is_none());
        self.indices[k.slot] = Some(self.keys.len());
        self.keys.push(k);
        self.positions.push(row.hot.position);
        self.velocities.push(row.hot.velocity);
        self.bounds.push(row.hot.bounds);
        self.lifecycle.push(row.hot.lifecycle);
        self.behavior.push(row.hot.behavior);
        self.cold.push(row.cold);
        self.extension.push(row.extension);
        k
    }
    fn despawn(&mut self, h: Key) -> bool {
        let Some(i) = self.index(h) else { return false };
        self.keys.swap_remove(i);
        self.positions.swap_remove(i);
        self.velocities.swap_remove(i);
        self.bounds.swap_remove(i);
        self.lifecycle.swap_remove(i);
        self.behavior.swap_remove(i);
        self.cold.swap_remove(i);
        self.extension.swap_remove(i);
        self.indices[h.slot] = None;
        if i < self.keys.len() {
            self.indices[self.keys[i].slot] = Some(i)
        }
        true
    }
    fn hot(&self, h: Key) -> Option<Hot> {
        self.index(h).map(|i| self.at(i))
    }
    fn read(&self, h: Key) -> Option<Row> {
        self.index(h).map(|i| Row {
            key: self.keys[i],
            hot: self.at(i),
            cold: self.cold[i].clone(),
            extension: self.extension[i],
        })
    }
    fn velocity(&mut self, h: Key, v: [i64; 3]) {
        let i = self.index(h).unwrap();
        self.velocities[i] = Velocity(v)
    }
    fn lifecycle(&mut self, h: Key, v: u32) {
        let i = self.index(h).unwrap();
        self.lifecycle[i] = Lifecycle(v)
    }
    fn extension(&mut self, h: Key, v: Option<Extension>) {
        let i = self.index(h).unwrap();
        self.extension[i] = v
    }
    fn tick(&mut self) {
        for i in 0..self.keys.len() {
            if self.lifecycle[i].0 == 1 {
                for axis in 0..3 {
                    self.positions[i].0[axis] =
                        self.positions[i].0[axis].wrapping_add(self.velocities[i].0[axis])
                }
            }
        }
    }
    fn scan(&mut self) -> Vec<(Key, Hot)> {
        self.keys
            .iter()
            .copied()
            .enumerate()
            .map(|(i, k)| (k, self.at(i)))
            .collect()
    }
}

#[derive(Default)]
pub struct Hecs {
    world: hecs::World,
}
impl Store for Hecs {
    type Handle = hecs::Entity;
    fn spawn(&mut self, r: Row) -> Self::Handle {
        let h = self.world.spawn((
            r.key,
            r.hot.position,
            r.hot.velocity,
            r.hot.bounds,
            r.hot.lifecycle,
            r.hot.behavior,
            r.cold,
        ));
        if let Some(v) = r.extension {
            self.world.insert_one(h, v).unwrap()
        }
        h
    }
    fn despawn(&mut self, h: Self::Handle) -> bool {
        self.world.despawn(h).is_ok()
    }
    fn hot(&self, h: Self::Handle) -> Option<Hot> {
        Some(Hot {
            position: *self.world.get::<&Position>(h).ok()?,
            velocity: *self.world.get::<&Velocity>(h).ok()?,
            bounds: *self.world.get::<&Bounds>(h).ok()?,
            lifecycle: *self.world.get::<&Lifecycle>(h).ok()?,
            behavior: *self.world.get::<&Behavior>(h).ok()?,
        })
    }
    fn read(&self, h: Self::Handle) -> Option<Row> {
        Some(Row {
            key: *self.world.get::<&Key>(h).ok()?,
            hot: self.hot(h)?,
            cold: (*self.world.get::<&Cold>(h).ok()?).clone(),
            extension: self.world.get::<&Extension>(h).ok().map(|v| *v),
        })
    }
    fn velocity(&mut self, h: Self::Handle, v: [i64; 3]) {
        self.world.get::<&mut Velocity>(h).unwrap().0 = v
    }
    fn lifecycle(&mut self, h: Self::Handle, v: u32) {
        self.world.get::<&mut Lifecycle>(h).unwrap().0 = v
    }
    fn extension(&mut self, h: Self::Handle, v: Option<Extension>) {
        if let Some(v) = v {
            self.world.insert_one(h, v).unwrap()
        } else {
            let _ = self.world.remove_one::<Extension>(h);
        }
    }
    fn tick(&mut self) {
        for (p, v, l) in self
            .world
            .query_mut::<(&mut Position, &Velocity, &Lifecycle)>()
        {
            if l.0 == 1 {
                for a in 0..3 {
                    p.0[a] = p.0[a].wrapping_add(v.0[a])
                }
            }
        }
    }
    fn scan(&mut self) -> Vec<(Key, Hot)> {
        self.world
            .query_mut::<(&Key, &Position, &Velocity, &Bounds, &Lifecycle, &Behavior)>()
            .into_iter()
            .map(|(k, p, v, b, l, t)| {
                (
                    *k,
                    Hot {
                        position: *p,
                        velocity: *v,
                        bounds: *b,
                        lifecycle: *l,
                        behavior: *t,
                    },
                )
            })
            .collect()
    }
}

type TickQuery = QueryState<(&'static mut Position, &'static Velocity, &'static Lifecycle)>;
type ScanQuery = QueryState<(
    &'static Key,
    &'static Position,
    &'static Velocity,
    &'static Bounds,
    &'static Lifecycle,
    &'static Behavior,
)>;
pub struct Bevy {
    world: BevyWorld,
    tick: TickQuery,
    scan: ScanQuery,
}
impl Default for Bevy {
    fn default() -> Self {
        let mut world = BevyWorld::new();
        let tick = world.query();
        let scan = world.query();
        Self { world, tick, scan }
    }
}
impl Store for Bevy {
    type Handle = bevy_ecs::entity::Entity;
    fn spawn(&mut self, r: Row) -> Self::Handle {
        let mut e = self.world.spawn((
            r.key,
            r.hot.position,
            r.hot.velocity,
            r.hot.bounds,
            r.hot.lifecycle,
            r.hot.behavior,
            r.cold,
        ));
        if let Some(v) = r.extension {
            e.insert(v);
        }
        e.id()
    }
    fn despawn(&mut self, h: Self::Handle) -> bool {
        self.world.despawn(h)
    }
    fn hot(&self, h: Self::Handle) -> Option<Hot> {
        Some(Hot {
            position: *self.world.get::<Position>(h)?,
            velocity: *self.world.get::<Velocity>(h)?,
            bounds: *self.world.get::<Bounds>(h)?,
            lifecycle: *self.world.get::<Lifecycle>(h)?,
            behavior: *self.world.get::<Behavior>(h)?,
        })
    }
    fn read(&self, h: Self::Handle) -> Option<Row> {
        Some(Row {
            key: *self.world.get::<Key>(h)?,
            hot: self.hot(h)?,
            cold: self.world.get::<Cold>(h)?.clone(),
            extension: self.world.get::<Extension>(h).copied(),
        })
    }
    fn velocity(&mut self, h: Self::Handle, v: [i64; 3]) {
        self.world.get_mut::<Velocity>(h).unwrap().0 = v
    }
    fn lifecycle(&mut self, h: Self::Handle, v: u32) {
        self.world.get_mut::<Lifecycle>(h).unwrap().0 = v
    }
    fn extension(&mut self, h: Self::Handle, v: Option<Extension>) {
        let mut e = self.world.entity_mut(h);
        if let Some(v) = v {
            e.insert(v);
        } else {
            e.remove::<Extension>();
        }
    }
    fn tick(&mut self) {
        for (mut p, v, l) in self.tick.iter_mut(&mut self.world) {
            if l.0 == 1 {
                for a in 0..3 {
                    p.0[a] = p.0[a].wrapping_add(v.0[a])
                }
            }
        }
    }
    fn scan(&mut self) -> Vec<(Key, Hot)> {
        self.scan
            .iter(&self.world)
            .map(|(k, p, v, b, l, t)| {
                (
                    *k,
                    Hot {
                        position: *p,
                        velocity: *v,
                        bounds: *b,
                        lifecycle: *l,
                        behavior: *t,
                    },
                )
            })
            .collect()
    }
}

#[derive(Default)]
pub struct Shipyard {
    world: shipyard::World,
}
impl Store for Shipyard {
    type Handle = shipyard::EntityId;
    fn spawn(&mut self, r: Row) -> Self::Handle {
        let h = self.world.add_entity((
            r.key,
            r.hot.position,
            r.hot.velocity,
            r.hot.bounds,
            r.hot.lifecycle,
            r.hot.behavior,
            r.cold,
        ));
        if let Some(v) = r.extension {
            self.world.add_component(h, (v,));
        }
        h
    }
    fn despawn(&mut self, h: Self::Handle) -> bool {
        self.world.delete_entity(h)
    }
    fn hot(&self, h: Self::Handle) -> Option<Hot> {
        Some(Hot {
            position: **self.world.get::<&Position>(h).ok()?,
            velocity: **self.world.get::<&Velocity>(h).ok()?,
            bounds: **self.world.get::<&Bounds>(h).ok()?,
            lifecycle: **self.world.get::<&Lifecycle>(h).ok()?,
            behavior: **self.world.get::<&Behavior>(h).ok()?,
        })
    }
    fn read(&self, h: Self::Handle) -> Option<Row> {
        Some(Row {
            key: **self.world.get::<&Key>(h).ok()?,
            hot: self.hot(h)?,
            cold: (**self.world.get::<&Cold>(h).ok()?).clone(),
            extension: self.world.get::<&Extension>(h).ok().map(|v| **v),
        })
    }
    fn velocity(&mut self, h: Self::Handle, v: [i64; 3]) {
        self.world.get::<&mut Velocity>(h).unwrap().0 = v
    }
    fn lifecycle(&mut self, h: Self::Handle, v: u32) {
        self.world.get::<&mut Lifecycle>(h).unwrap().0 = v
    }
    fn extension(&mut self, h: Self::Handle, v: Option<Extension>) {
        if let Some(v) = v {
            self.world.add_component(h, (v,));
        } else {
            self.world.remove::<(Extension,)>(h);
        }
    }
    fn tick(&mut self) {
        let (mut p, v, l) = self
            .world
            .borrow::<(ViewMut<Position>, View<Velocity>, View<Lifecycle>)>()
            .unwrap();
        for (p, v, l) in (&mut p, &v, &l).iter() {
            if l.0 == 1 {
                for a in 0..3 {
                    p.0[a] = p.0[a].wrapping_add(v.0[a])
                }
            }
        }
    }
    fn scan(&mut self) -> Vec<(Key, Hot)> {
        let (k, p, v, b, l, t) = self
            .world
            .borrow::<(
                View<Key>,
                View<Position>,
                View<Velocity>,
                View<Bounds>,
                View<Lifecycle>,
                View<Behavior>,
            )>()
            .unwrap();
        (&k, &p, &v, &b, &l, &t)
            .iter()
            .map(|(k, p, v, b, l, t)| {
                (
                    *k,
                    Hot {
                        position: *p,
                        velocity: *v,
                        bounds: *b,
                        lifecycle: *l,
                        behavior: *t,
                    },
                )
            })
            .collect()
    }
}

pub struct Registry<S: Store> {
    pub store: S,
    slots: Vec<(u64, Option<S::Handle>)>,
    pub world: u64,
}
impl<S: Store> Registry<S> {
    pub fn new(world: u64) -> Self {
        Self {
            store: S::default(),
            slots: Vec::new(),
            world,
        }
    }
    pub fn key(&self, slot: usize) -> Option<Key> {
        let (g, h) = self.slots.get(slot)?;
        h.map(|_| Key {
            world: self.world,
            slot,
            generation: *g,
        })
    }
    fn handle(&self, k: Key) -> Option<S::Handle> {
        if k.world != self.world {
            return None;
        }
        let (g, h) = self.slots.get(k.slot)?;
        if *g == k.generation {
            *h
        } else {
            None
        }
    }
    pub fn spawn(&mut self, slot: usize, seed: u64) -> Result<Key, &'static str> {
        if slot >= 20000 || seed > 1_000_000 {
            return Err("LIMIT");
        }
        self.slots.resize(self.slots.len().max(slot + 1), (1, None));
        let (g, h) = &mut self.slots[slot];
        if h.is_some() {
            return Err("OCCUPIED");
        }
        let k = Key {
            world: self.world,
            slot,
            generation: *g,
        };
        *h = Some(self.store.spawn(fixture(k, seed)));
        Ok(k)
    }
    pub fn despawn(&mut self, k: Key) -> Result<(), &'static str> {
        let h = self.handle(k).ok_or("STALE")?;
        let next = k.generation.checked_add(1).ok_or("EXHAUSTED")?;
        assert!(self.store.despawn(h));
        self.slots[k.slot] = (next, None);
        Ok(())
    }
    pub fn read(&self, k: Key) -> Option<Row> {
        self.store.read(self.handle(k)?)
    }
    pub fn hot(&self, k: Key) -> Option<Hot> {
        self.store.hot(self.handle(k)?)
    }
    pub fn velocity(&mut self, k: Key, v: [i64; 3]) -> Result<(), &'static str> {
        let h = self.handle(k).ok_or("STALE")?;
        self.store.velocity(h, v);
        Ok(())
    }
    pub fn lifecycle(&mut self, k: Key, v: u32) -> Result<(), &'static str> {
        // Resolve identity first, matching every command in the reference model.
        let h = self.handle(k).ok_or("STALE")?;
        if v > 1 {
            return Err("LIMIT");
        }
        self.store.lifecycle(h, v);
        Ok(())
    }
    pub fn extension(&mut self, k: Key, v: Option<Extension>) -> Result<(), &'static str> {
        let h = self.handle(k).ok_or("STALE")?;
        self.store.extension(h, v);
        Ok(())
    }
    pub fn snapshot(&mut self) -> Vec<Row> {
        let mut keys = self
            .store
            .scan()
            .into_iter()
            .map(|(k, _)| k)
            .collect::<Vec<_>>();
        keys.sort_unstable();
        assert!(keys.windows(2).all(|w| w[0] != w[1]));
        keys.into_iter()
            .map(|k| self.read(k).expect("registry/backend disagreement"))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn registry<S: Store>() {
        let mut r = Registry::<S>::new(9);
        let a = r.spawn(0, 4).unwrap();
        let b = r.spawn(1, 7).unwrap();
        assert_eq!(r.spawn(0, 5), Err("OCCUPIED"));
        let cold = r.read(b).unwrap().cold;
        r.extension(b, Some(Extension(8))).unwrap();
        r.store.tick();
        assert_eq!(r.read(b).unwrap().cold, cold);
        r.despawn(a).unwrap();
        let newer = r.spawn(0, 6).unwrap();
        assert_eq!(newer.generation, 2);
        assert!(r.read(a).is_none());
        assert!(r.read(Key { world: 10, ..newer }).is_none());
        assert_eq!(r.velocity(a, [9; 3]), Err("STALE"));
        assert_eq!(r.despawn(a), Err("STALE"));
        assert_eq!(r.lifecycle(a, 2), Err("STALE"));
        assert_eq!(r.lifecycle(newer, 2), Err("LIMIT"));
        assert_eq!(r.read(newer).unwrap().hot.lifecycle.0, 1);
        r.extension(b, None).unwrap();
        assert!(r.read(b).unwrap().extension.is_none());
        assert_eq!(r.snapshot().len(), 2);
        assert!(r.read(b).is_some());
    }
    fn backend<S: Store>() {
        let mut b = S::default();
        let key = Key {
            world: 9,
            slot: 0,
            generation: 1,
        };
        let old = b.spawn(fixture(key, 1));
        assert!(b.despawn(old));
        let new = b.spawn(fixture(
            Key {
                generation: 2,
                ..key
            },
            2,
        ));
        assert!(b.hot(old).is_none());
        assert!(b.hot(new).is_some());
        assert!(!b.despawn(old));
    }
    #[test]
    fn soa_registry() {
        registry::<Soa>()
    }
    #[test]
    fn hecs_registry() {
        registry::<Hecs>()
    }
    #[test]
    fn bevy_registry() {
        registry::<Bevy>()
    }
    #[test]
    fn shipyard_registry() {
        registry::<Shipyard>()
    }
    #[test]
    fn soa_stale_backend() {
        backend::<Soa>()
    }
    #[test]
    fn hecs_stale_backend() {
        backend::<Hecs>()
    }
    #[test]
    fn bevy_stale_backend() {
        backend::<Bevy>()
    }
    #[test]
    fn shipyard_stale_backend() {
        backend::<Shipyard>()
    }
}
